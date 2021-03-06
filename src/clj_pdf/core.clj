(ns clj-pdf.core
  (:use clojure.walk
        [clojure.set :only (rename-keys)]
        [clojure.string :only [split]])
  (:require [clj-pdf.charting :as charting]
            [clj-pdf.svg :as svg]
            [clj-pdf.graphics-2d :as g2d])
  (:import
    java.awt.Color
    [com.lowagie.text.pdf.draw DottedLineSeparator LineSeparator]
    sun.misc.BASE64Decoder
    [com.lowagie.text
     Anchor
     Annotation
     Cell
     ChapterAutoNumber
     Chunk
     Document
     Element
     Font
     FontFactory
     GreekList
     HeaderFooter
     Image
     List
     ListItem
     PageSize
     Paragraph
     Phrase
     Rectangle
     RectangleReadOnly
     RomanList
     Section
     Table
     ZapfDingbatsList
     ZapfDingbatsNumberList]
    [com.lowagie.text.pdf BaseFont PdfContentByte PdfReader PdfStamper PdfWriter PdfPCell PdfPTable ]
    [java.io PushbackReader InputStream InputStreamReader FileOutputStream ByteArrayOutputStream]))

(declare ^:dynamic *cache*)
(def fonts-registered? (atom nil))

(declare make-section)

(defn- styled-item [meta item]
  (make-section meta (if (string? item) [:chunk item] item)))

(defn- pdf-styled-item [meta item]
  (make-section meta (if (string? item) [:phrase item] item)))

(defn- get-alignment [align]
  (condp = (when align (name align))
    "left"      Element/ALIGN_LEFT
    "center"    Element/ALIGN_CENTER
    "right"     Element/ALIGN_RIGHT
    "justified" Element/ALIGN_JUSTIFIED
    "top"       Element/ALIGN_TOP
    "middle"    Element/ALIGN_MIDDLE
    "bottom"    Element/ALIGN_BOTTOM
    Element/ALIGN_LEFT))

(defn- set-background [element {:keys [background]}]
  (when background
    (let [[r g b] background] (.setBackground element (Color. r g b)))))

(defn get-style [style]
  (condp = (when style (name style))
        "bold"        Font/BOLD
        "italic"      Font/ITALIC
        "bold-italic" Font/BOLDITALIC
        "normal"      Font/NORMAL
        "strikethru"  Font/STRIKETHRU
        "underline"   Font/UNDERLINE
        Font/NORMAL))

(defn- compute-font-style [styles]
  (if (> (count styles) 1)
    (apply bit-or (map get-style styles))
    (get-style (first styles))))

(defn- font
  [{style    :style
    styles   :styles
    size     :size
    [r g b]  :color
    family   :family
    ttf-name :ttf-name
    encoding :encoding}]
    (FontFactory/getFont
      (if-not (nil? ttf-name)
        ttf-name
        (condp = (when family (name family))
          "courier"      FontFactory/COURIER
          "helvetica"    FontFactory/HELVETICA
          "times-roman"  FontFactory/TIMES_ROMAN
          "symbol"       FontFactory/SYMBOL
          "zapfdingbats" FontFactory/ZAPFDINGBATS
          FontFactory/HELVETICA))

      (case [(not (nil? ttf-name)) encoding]
        [true :unicode] BaseFont/IDENTITY_H
        [true :default] BaseFont/WINANSI
        BaseFont/WINANSI)

      true

      (float (or size 10))
      (cond
        styles (compute-font-style styles)
        style (get-style style)
        :else Font/NORMAL)

      (if (and r g b)
        (new Color r g b)
        (new Color 0 0 0))))

(defn- custom-page-size [width height]
  (RectangleReadOnly. width height))

(defn- page-size [size]
  (if (vector? size)
    (apply custom-page-size size)
    (condp = (when size (name size))
      "a0"                        PageSize/A0
      "a1"                        PageSize/A1
      "a2"                        PageSize/A2
      "a3"                        PageSize/A3
      "a4"                        PageSize/A4
      "a5"                        PageSize/A5
      "a6"                        PageSize/A6
      "a7"                        PageSize/A7
      "a8"                        PageSize/A8
      "a9"                        PageSize/A9
      "a10"                       PageSize/A10
      "arch-a"                    PageSize/ARCH_A
      "arch-b"                    PageSize/ARCH_B
      "arch-c"                    PageSize/ARCH_C
      "arch-d"                    PageSize/ARCH_D
      "arch-e"                    PageSize/ARCH_E
      "b0"                        PageSize/B0
      "b1"                        PageSize/B1
      "b2"                        PageSize/B2
      "b3"                        PageSize/B3
      "b4"                        PageSize/B4
      "b5"                        PageSize/B5
      "b6"                        PageSize/B6
      "b7"                        PageSize/B7
      "b8"                        PageSize/B8
      "b9"                        PageSize/B9
      "b10"                       PageSize/B10
      "crown-octavo"              PageSize/CROWN_OCTAVO
      "crown-quarto"              PageSize/CROWN_QUARTO
      "demy-octavo"               PageSize/DEMY_OCTAVO
      "demy-quarto"               PageSize/DEMY_QUARTO
      "executive"                 PageSize/EXECUTIVE
      "flsa"                      PageSize/FLSA
      "flse"                      PageSize/FLSE
      "halfletter"                PageSize/HALFLETTER
      "id-1"                      PageSize/ID_1
      "id-2"                      PageSize/ID_2
      "id-3"                      PageSize/ID_3
      "large-crown-octavo"        PageSize/LARGE_CROWN_OCTAVO
      "large-crown-quarto"        PageSize/LARGE_CROWN_QUARTO
      "ledger"                    PageSize/LEDGER
      "legal"                     PageSize/LEGAL
      "letter"                    PageSize/LETTER
      "note"                      PageSize/NOTE
      "penguin-large-paperback"   PageSize/PENGUIN_LARGE_PAPERBACK
      "penguin-small-paperback"   PageSize/PENGUIN_SMALL_PAPERBACK
      "postcard"                  PageSize/POSTCARD
      "royal-octavo"              PageSize/ROYAL_OCTAVO
      "royal-quarto"              PageSize/ROYAL_QUARTO
      "small-paperback"           PageSize/SMALL_PAPERBACK
      "tabloid"                   PageSize/TABLOID
      PageSize/A4)))

(defn- page-orientation [page-size orientation]
  (if page-size
    (condp = (if orientation (name orientation))
      "landscape"    (.rotate page-size)
      page-size)))


(defn- chapter [meta & [title & sections]]
  (let [ch (new ChapterAutoNumber
                (make-section meta (if (string? title) [:paragraph title] title)))]
    (doseq [section sections]
      (make-section (assoc meta :parent ch) section))
    ch))


(defn- heading [meta & content]
  (make-section
    (into [:paragraph (merge meta (merge {:size 18 :style :bold} (:style meta)))] content)))


(defn- paragraph [meta & content]
  (let [paragraph (new Paragraph)
        {:keys [first-line-indent indent keep-together leading align]} meta]

    (.setFont paragraph (font meta))
    (if keep-together (.setKeepTogether paragraph true))
    (if first-line-indent (.setFirstLineIndent paragraph (float first-line-indent)))
    (if indent (.setIndentationLeft paragraph (float indent)))
    (if leading (.setLeading paragraph (float leading)))
    (if align (.setAlignment paragraph (get-alignment align)))

    (doseq [item content]
      (.add paragraph
        (make-section
          meta
          (if (string? item) [:chunk item] item))))

    paragraph ))


(defn- li [{:keys [numbered
                   lettered
                   roman
                   greek
                   dingbats
                   dingbats-char-num
                   dingbatsnumber
                   dingbatsnumber-type
                   lowercase
                   indent
                   symbol] :as meta}
           & items]
  (let [list (cond
               roman           (new RomanList)
               greek           (new GreekList)
               dingbats        (new ZapfDingbatsList dingbats-char-num)
               dingbatsnumber  (new ZapfDingbatsNumberList dingbatsnumber-type)
               :else (new List (or numbered false) (or lettered false)))]

    (if lowercase (.setLowercase list lowercase))
    (if indent (.setIndentationLeft list (float indent)))
    (if symbol (.setListSymbol list symbol))

    (doseq [item items]
      (.add list (new ListItem (styled-item meta item))))
    list))


(defn- phrase
  [meta & content]
  (let [leading (:leading meta)
        p (doto (new Phrase)
            (.setFont (font meta))
            (.addAll (map (partial make-section meta) content)))]
    (if leading (.setLeading p (float leading))) p))

(defn- text-chunk [style content]
  (let [ch (new Chunk (make-section content) (font style))]
    (set-background ch style)
    (cond
      (:super style) (.setTextRise ch (float 5))
      (:sub style) (.setTextRise ch (float -4))
      :else ch)))


(defn- annotation
  ([_ title text] (annotation title text))
  ([title text] (new Annotation title text)))


(defn- anchor [{:keys [style leading id target] :as meta} content]
  (let [a (cond (and style leading) (new Anchor (float leading) content (font style))
                leading             (new Anchor (float leading) (styled-item meta content))
                style               (new Anchor content (font style))
                :else               (new Anchor (styled-item meta content)))]
    (if id (.setName a id))
    (if target (.setReference a target))
    a))


(defn- get-border [borders]
  (reduce +
    (vals
      (select-keys
        {:top Cell/TOP :bottom Cell/BOTTOM :left Cell/LEFT :right Cell/RIGHT}
        borders))))



(defn- cell [{:keys [background-color
                     colspan
                     rowspan
                     border
                     align
                     set-border
                     border-color
                     border-width
                     border-width-bottom
                     border-width-left
                     border-width-right
                     border-width-top] :as meta}
             content]

  (let [c (if (string? content) (new Cell (styled-item meta content)) (new Cell))
        [r g b] background-color]

    (if (and r g b) (.setBackgroundColor c (new Color (int r) (int g) (int b))))
    (when (not (nil? border))
      (.setBorder c (if border Rectangle/BOX Rectangle/NO_BORDER)))

    (if rowspan (.setRowspan c (int rowspan)))
    (if colspan (.setColspan c (int colspan)))
    (if set-border (.setBorder c (int (get-border set-border))))
    (if border-width (.setBorderWidth c (float border-width)))
    (if border-width-bottom (.setBorderWidthBottom c (float border-width-bottom)))
    (if border-width-left (.setBorderWidthLeft c (float border-width-left)))
    (if border-width-right (.setBorderWidthRight c  (float border-width-right)))
    (if border-width-top (.setBorderWidthTop c (float border-width-top)))
    (.setHorizontalAlignment c (get-alignment align))

    (if (string? content) c (doto c (.addElement (make-section meta content))))))


(defn- pdf-cell-padding*
  ([cell a]       (.setPadding cell (float a)))
  ([cell a b]     (pdf-cell-padding* cell a b a b))
  ([cell a b c]   (pdf-cell-padding* cell a b c b))
  ([cell a b c d]
   (doto cell
     (.setPaddingTop    (float a))
     (.setPaddingRight  (float b))
     (.setPaddingBottom (float c))
     (.setPaddingLeft   (float d)))))

(defn- pdf-cell-padding [cell pad]
  (when-let [args (if (sequential? pad) pad [pad])]
    (apply pdf-cell-padding* cell args)))

(defn- pdf-cell [{:keys [background-color
                         colspan
                         rowspan
                         border
                         align
                         valign
                         set-border
                         border-color
                         border-width
                         border-width-bottom
                         border-width-left
                         border-width-right
                         border-width-top
                         padding
                         padding-bottom
                         padding-left
                         padding-right
                         padding-top
                         rotation
                         height
                         min-height] :as meta}
                 content]
  (let [c (if (string? content) (new PdfPCell (pdf-styled-item meta content)) (new PdfPCell))]

    (let [[r g b] background-color]
      (if (and r g b) (.setBackgroundColor c (new Color (int r) (int g) (int b)))))

    (let [[r g b] border-color]
      (if (and r g b) (.setBorderColor c (new Color (int r) (int g) (int b)))))

    (when (not (nil? border))
      (.setBorder c (if border Rectangle/BOX Rectangle/NO_BORDER)))

    (if rowspan (.setRowspan c (int rowspan)))
    (if colspan (.setColspan c (int colspan)))
    (if set-border (.setBorder c (int (get-border set-border))))
    (if border-width (.setBorderWidth c (float border-width)))
    (if border-width-bottom (.setBorderWidthBottom c (float border-width-bottom)))
    (if border-width-left (.setBorderWidthLeft c (float border-width-left)))
    (if border-width-right (.setBorderWidthRight c  (float border-width-right)))
    (if border-width-top (.setBorderWidthTop c (float border-width-top)))
    (if padding (pdf-cell-padding c padding))
    (if padding-bottom (.setPaddingBottom c (float padding-bottom)))
    (if padding-left (.setPaddingLeft c (float padding-left)))
    (if padding-right (.setPaddingRight c  (float padding-right)))
    (if padding-top (.setPaddingTop c (float padding-top)))
    (if rotation (.setRotation c (int rotation)))
    (if height (.setFixedHeight c (float height)))
    (if min-height (.setMinimumHeight c (float min-height)))
    (.setHorizontalAlignment c (get-alignment align))
    (.setVerticalAlignment c (get-alignment valign))

    (if (string? content) c (doto c (.addElement (make-section meta content))))))


(defn- table-header [tbl header cols]
  (when header
    (let [meta? (map? (first header))
          header-rest (if meta? (rest header) header)
          header-data header-rest
          set-bg #(if-let [[r g b] (if meta? (:backdrop-color (first header)))]
                    (doto % (.setBackgroundColor (new Color (int r) (int g) (int b)))) %)]
      (if (= 1 (count header-data))
        (let [header (first header-data)
               header-text (if (string? header)
                            (make-section [:chunk {:style "bold"} header])
                            (make-section header))
              header-cell (doto (new Cell header-text)
                            (.setHorizontalAlignment 1)
                            (.setHeader true)
                            (.setColspan cols))]
          (set-bg header-cell)
          (.addCell tbl header-cell))

        (doseq [h header-data]
          (let [header-text (if (string? h)
                              (make-section [:chunk {:style "bold"} h])
                              (make-section h))
                header-cell (doto (new Cell header-text) (.setHeader true))]
            (when-not (and (string? h)
                           (map? (second h)))
              (when-let [align (:align (second h))]
                (.setHorizontalAlignment header-cell (get-alignment align))))
            (set-bg header-cell)
            (.addCell tbl header-cell)))))
    (.endHeaders tbl)))

(declare split-classes-from-tag)

(defn- add-table-cell
  [tbl meta content]
  (let [[tag & classes] (when (vector? content)
                          (split-classes-from-tag (first content)))
        element (cond
                  (= tag :cell)     content
                  (nil? content)    [:cell [:chunk meta ""]]
                  (string? content) [:cell [:chunk meta content]]
                  :else             [:cell content])]
    (.addCell tbl (make-section meta element))))

(defn- table [{:keys [background-color spacing padding offset header border border-width cell-border width widths align num-cols]
               :as meta}
              & rows]
  (when (< (count rows) 1) (throw (new Exception "Table must contain rows!")))

  (let [header-cols (cond-> (count header)
                      (map? (first header)) dec)
        cols (or num-cols (apply max (cons header-cols (map count rows))))
        tbl  (doto (new Table cols (count rows)) (.setWidth (float (or width 100))))]

    (when widths
      (if (= (count widths) cols)
        (.setWidths tbl (int-array widths))
        (throw (new Exception (str "wrong number of columns specified in widths: " widths ", number of columns: " cols)))))

    (if (= false border)
      (.setBorder tbl Rectangle/NO_BORDER)
      (when border-width (.setBorderWidth tbl (float border-width))))

    (when (= false cell-border)
      (.setDefaultCell tbl (doto (new Cell) (.setBorder Rectangle/NO_BORDER))))

    (if background-color (let [[r g b] background-color] (.setBackgroundColor tbl (new Color (int r) (int g) (int b)))))
    (.setPadding tbl (if padding (float padding) (float 3)))
    (if spacing (.setSpacing tbl (float spacing)))
    (if offset (.setOffset tbl (float offset)))
    (table-header tbl header cols)

    (.setAlignment tbl (get-alignment align))

    (doseq [row rows]
      (doseq [column row]
        (add-table-cell tbl (dissoc meta :header :align :offset :num-cols :width :widths) column)))

    tbl))

(defn- add-pdf-table-cell
  [tbl meta content]
  (let [[tag & classes] (when (vector? content)
                          (split-classes-from-tag (first content)))
        element (cond
                  (= tag :pdf-cell) content
                  (nil? content)    [:pdf-cell [:chunk meta ""]]
                  (string? content) [:pdf-cell [:chunk meta content]]
                  :else             [:pdf-cell content])]
    (.addCell tbl (make-section meta element))))

(defn- pdf-table [{:keys [spacing-before spacing-after cell-border bounding-box num-cols horizontal-align table-events width-percent]
                  :as meta}
                  widths
                  & rows]
  (when (empty? rows) (throw (new Exception "Table must contain at least one row")))
  (when (not= (count widths) (or num-cols (apply max (map count rows))))
    (throw (new Exception (str "wrong number of columns specified in widths: " widths ", number of columns: " (or num-cols (apply max (map count rows)))))))

  (let [cols (or num-cols (apply max (map count rows)))
        tbl (new PdfPTable cols)]

    (when width-percent (.setWidthPercentage tbl (float width-percent)))

    (if bounding-box
      (let [[x y] bounding-box]
        (.setWidthPercentage tbl (float-array widths) (make-section [:rectangle x y])))
      (.setWidths tbl (float-array widths)))

    (doseq [table-event table-events]
      (.setTableEvent tbl table-event))

    (if spacing-before (.setSpacingBefore tbl (float spacing-before)))
    (if spacing-after (.setSpacingAfter tbl (float spacing-after)))

    (.setHorizontalAlignment tbl (get-alignment horizontal-align))

    (doseq [row rows]
      (doseq [column row]
        (add-pdf-table-cell tbl (merge meta (when (= false cell-border) {:set-border []})) column)))

    tbl))
(defn- make-image [{:keys [scale
                           xscale
                           yscale
                           align
                           width
                           height
                           base64
                           rotation
                           annotation
                           pad-left
                           pad-right
                           left-margin
                           right-margin
                           top-margin
                           bottom-margin
                           page-width
                           page-height]}
                   img-data]
  (let [img (cond
              (instance? java.awt.Image img-data)
              (Image/getInstance (.createImage (java.awt.Toolkit/getDefaultToolkit) (.getSource img-data)) nil)

              base64
              (Image/getInstance (.createImage (java.awt.Toolkit/getDefaultToolkit) (.decodeBuffer (new BASE64Decoder) img-data)) nil)

              (= Byte/TYPE (.getComponentType (class img-data)))
              (Image/getInstance (.createImage (java.awt.Toolkit/getDefaultToolkit) img-data) nil)

              (or (string? img-data) (instance? java.net.URL img-data))
              (Image/getInstance img-data)

              :else
              (throw (new Exception (str "Unsupported image data: " img-data ", must be one of java.net.URL, java.awt.Image, or filename string"))))
        img-width (.getWidth img)
        img-height (.getHeight img)]
    (if rotation (.setRotation img (float rotation)))
    (if align (.setAlignment img (get-alignment align)))
    (if annotation (let [[title text] annotation] (.setAnnotation img (make-section [:annotation title text]))))
    (if pad-left (.setIndentationLeft img (float pad-left)))
    (if pad-right (.setIndentationRight img (float pad-right)))

    ;;scale relative to page size
    (if (and page-width page-height left-margin right-margin top-margin bottom-margin)
      (let [available-width (- page-width (+ left-margin right-margin))
            available-height (- page-height (+ top-margin bottom-margin))
            page-scale (* 100
                          (cond
                            (and (> img-width available-width)
                                 (> img-height available-height))
                            (if (> img-width img-height)
                              (/ available-width img-width)
                              (/ available-height img-height))

                            (> img-width available-width)
                            (/ available-width img-width)

                            (> img-height available-height)
                            (/ available-height img-height)

                            :else 1))]
        (cond
          (and xscale yscale) (.scalePercent img (float (* page-scale xscale)) (float (* page-scale yscale)))
          xscale (.scalePercent img (float (* page-scale xscale)) (float 100))
          yscale (.scalePercent img (float 100) (float (* page-scale yscale)))
          :else (when (or (>  img-width available-width) (>  img-height available-height))
                  (.scalePercent img (float page-scale))))))

    (if width (.scaleAbsoluteWidth img (float width)))
    (if height (.scaleAbsoluteHeight img (float height)))
    (if scale (.scalePercent img scale))
    img))

(defn- image [& [meta img-data :as params]]
  (let [image-hash (.hashCode params)]
    (if-let [cached (get @*cache* image-hash)]
      cached
      (let [compiled (make-image meta img-data)]
        (swap! *cache* assoc image-hash compiled)
        compiled))))

(defn- section [meta & [title & content]]
  (let [sec (.addSection (:parent meta)
              (make-section meta (if (string? title) [:paragraph title] title)))
        indent (:indent meta)]
    (if indent (.setIndentation sec (float indent)))
    (doseq [item content]
      (if (and (coll? item) (= "section" (name (first item))))
        (make-section (assoc meta :parent sec) item)
        (.add sec (make-section meta (if (string? item) [:chunk item] item)))))))


(defn- subscript [meta text]
  (text-chunk (assoc meta :sub true) text))


(defn- superscript [meta text]
  (text-chunk (assoc meta :super true) text))

(defn- make-chart [& [meta & more :as params]]
  (let [{:keys [vector align width height page-width page-height]} meta]
    (if vector
      (apply charting/chart params)
      (image
        (cond
          (and align width height) meta
          (and width height) (assoc meta :align :center)
          align (assoc meta :width (* 0.85 page-width) :height (* 0.85 page-height))
          :else (assoc meta
                    :align :center
                    :width (* 0.85 page-width)
                    :height (* 0.85 page-height)))
        (apply charting/chart params)))))

(defn- chart [& params]
  (let [chart-hash (.hashCode params)]
    (if-let [cached (get @*cache* chart-hash)]
      cached
      (let [compiled (apply make-chart params)]
        (swap! *cache* assoc chart-hash compiled)
        compiled))))

(defn- svg-element [& params]
  (let [svg-hash (.hashCode params)]
    (if-let [cached (get *cache* svg-hash)]
      cached
      (let [compiled (apply svg/render params)]
        (swap! *cache* assoc svg-hash compiled)
        compiled))))

(defn- line [{dotted? :dotted, gap :gap} & _]
  (doto (if dotted?
          (if gap
            (doto (new DottedLineSeparator) (.setGap (float gap)))
            (new DottedLineSeparator))
          (new LineSeparator))
    (.setOffset -5)))

(defn- reference [meta reference-id]
  (if-let [item (get @*cache* reference-id)]
    item
    (if-let [item (get-in meta [:references reference-id])]
      (let [item (make-section item)]
        (swap! *cache* assoc reference-id item)
        item)
      (throw (Exception. (str "reference tag not found: " reference-id))))))

(defn- spacer
  ([_] (make-section [:paragraph {:leading 12} "\n"]))
  ([_ height]
    (make-section [:paragraph {:leading 12} (apply str (take height (repeat "\n")))])))

(defn- rectangle
  [_ width height]
  (new Rectangle width height))

(defn- split-classes-from-tag
  [tag]
  (map keyword (split (name tag) #"\.")))

(defn- get-class-attributes
  [stylesheet classes]
  (apply merge (map stylesheet classes)))

(defn- make-section
  ([element] (if element (make-section {} element) ""))
  ([meta element]
    (cond
      (string? element) element
      (nil? element) ""
      (number? element) (str element)
      :else
      (let [[element-name & [h & t :as content]] element
            tag (if (string? element-name) (keyword element-name) element-name)
            [tag & classes] (split-classes-from-tag tag)
            class-attrs (get-class-attributes (:stylesheet meta) classes)
            new-meta (cond-> meta
                       class-attrs (merge class-attrs)
                       (map? h)    (merge h))
            elements (if (map? h) t content)]

        (apply
          (condp = tag
            :anchor      anchor
            :annotation  annotation
            :cell        cell
            :pdf-cell    pdf-cell
            :chapter     chapter
            :chart       chart
            :chunk       text-chunk
            :heading     heading
            :image       image
            :graphics    g2d/with-graphics
            :svg         svg-element
            :line        line
            :list        li
            :paragraph   paragraph
            :phrase      phrase
            :reference   reference
            :rectangle   rectangle
            :section     section
            :spacer      spacer
            :superscript superscript
            :subscript   subscript
            :table       table
            :pdf-table   pdf-table
            (throw (new Exception (str "invalid tag: " tag " in element: " element) )))
          (cons new-meta elements))))))

(declare append-to-doc)

(defn- clear-double-page [stylesheet references font-style width height item doc pdf-writer]
  "End current page and make sure that subsequent content will start on
     the next odd-numbered page, inserting a blank page if necessary."
  (let [append (fn [item] (append-to-doc stylesheet references font-style width height item doc pdf-writer))]
    ;; Inserting a :pagebreak starts a new page, unless we already happen to
    ;; be on a blank page, in which case it does nothing;
    (append [:pagebreak])
    ;; in either case we're now on a blank page, and if it's even-numbered,
    ;; we need to insert some whitespace to force the next :pagebreak to start
    ;; a new, odd-numbered page.
    (when (even? (.getPageNumber pdf-writer))
      (append [:paragraph " "])
      (append [:pagebreak]))))

(defn- append-to-doc [stylesheet references font-style width height item doc pdf-writer]
  (cond
    (= [:pagebreak] item) (.newPage doc)
    (= [:clear-double-page] item) (clear-double-page stylesheet references font-style width height item doc pdf-writer)
    :else (.add doc
                (make-section
                 (assoc font-style
                        :stylesheet stylesheet
                        :references references
                        :left-margin (.leftMargin doc)
                        :right-margin (.rightMargin doc)
                        :top-margin (.topMargin doc)
                        :bottom-margin (.bottomMargin doc)
                        :page-width width
                        :page-height height
                        :pdf-writer pdf-writer)
                 (or item [:paragraph item])))))

 (defn- add-header [header doc]
   (if header
          (.setHeader doc
            (doto (new HeaderFooter (new Phrase header) false) (.setBorderWidthTop 0)))))

(defn- setup-doc [{:keys [left-margin
                         right-margin
                         top-margin
                         bottom-margin
                         title
                         subject
                         doc-header
                         header
                         letterhead
                         footer
                         pages
                         author
                         creator
                         size
                         font-style
                         orientation
                         page-events]}
                  out]

  (let [[nom head] doc-header
        doc            (new Document (page-orientation (page-size size) orientation))
        width          (.. doc getPageSize getWidth)
        height         (.. doc getPageSize getHeight)
        output-stream  (if (string? out) (new FileOutputStream out) out)
        temp-stream    (if (or pages (not (empty? page-events))) (new ByteArrayOutputStream))
        footer         (when (not= footer false)
                         (if (string? footer)
                           {:text footer :align :right :start-page 1}
                           (merge {:align :right :start-page 1} footer)))
        page-numbers? (not= false (:page-numbers footer))]

    ;;header and footer must be set before the doc is opened, or itext will not put them on the first page!
    ;;if we have to print total pages, then the document has to be post processed
    (let [output-stream-to-use (if (or pages (not (empty? page-events))) temp-stream output-stream)
          pdf-writer (PdfWriter/getInstance doc output-stream-to-use)]
      (when-not pages
        (doseq [page-event page-events]
          (.setPageEvent pdf-writer page-event))
        (if footer
          (.setFooter doc
            (doto (new HeaderFooter (new Phrase (str (:text footer) " ") (font {:size 10})) page-numbers?)
              (.setBorder 0)
              (.setAlignment (get-alignment (:align footer)))))))

      ;;must set margins before opening the doc
      (if (and left-margin right-margin top-margin bottom-margin)
        (.setMargins doc
          (float left-margin)
          (float right-margin)
          (float top-margin)
          (float (if pages (+ 20 bottom-margin) bottom-margin))))


      ;;if we have a letterhead then we want to put it on the first page instead of the header,
      ;;so we will open doc beofore adding the header
      (if  letterhead
        (do
          (.open doc)
          (doseq [item letterhead]
            (append-to-doc nil nil (or font-style {})  width height (if (string? item) [:paragraph item] item) doc pdf-writer))
          (add-header header doc))
        (do
          (add-header header doc)
          (.open doc)))


      (if title (.addTitle doc title))
      (if subject (.addSubject doc subject))
      (if (and nom head) (.addHeader doc nom head))
      (if author (.addAuthor doc author))
      (if creator (.addCreator doc creator))

      [doc width height temp-stream output-stream pdf-writer])))

(defn- write-pages [temp-stream output-stream]
  (.writeTo temp-stream output-stream)
  (.flush output-stream)
  (.close output-stream))

(defn- align-footer [page-width base-font {:keys [text align]}]
  (let [font-width (.getWidthPointKerned base-font (or text "") (float 10))]
    (float
      (condp = align
        :right  (- page-width (+ 50 font-width))
        :left   (+ 50 font-width)
        :center (- (/ page-width 2) (/ font-width 2))))))

(defn- write-total-pages [doc width {:keys [footer footer-separator]} temp-stream output-stream]
  (let [reader    (new PdfReader (.toByteArray temp-stream))
        stamper   (new PdfStamper reader, output-stream)
        num-pages (.getNumberOfPages reader)
        base-font (BaseFont/createFont)
        footer    (when (not= footer false)
                    (if (string? footer)
                      {:text footer :align :right :start-page 1}
                      (merge {:align :right :start-page 1} footer)))]
    (when footer
      (dotimes [i num-pages]
        (if (>= i (dec (or (:start-page footer) 1)))
          (doto (.getOverContent stamper (inc i))
            (.beginText)
            (.setFontAndSize base-font 10)
            (.setTextMatrix
             (align-footer width base-font footer) (float 20))
            (.showText (str (:text footer) " " (inc i) (or (:footer-separator footer) " / ") num-pages))
            (.endText)))))
    (.close stamper)))

(defn- preprocess-item [item]
  (cond
    (string? item)
    [:paragraph item]

    ;;iText page breaks on tables are broken,
    ;;this ensures that table will not spill over other content
    (= :table (first item))
    [:paragraph {:leading 20} item]

    :else item))

(defn- add-item [item {:keys [stylesheet font references]} width height doc pdf-writer]
  (if (and (coll? item) (coll? (first item)))
    (doseq [element item]
      (append-to-doc stylesheet references font width height (preprocess-item element) doc pdf-writer))
    (append-to-doc stylesheet references font width height (preprocess-item item) doc pdf-writer)))

(defn register-fonts [doc-meta]
  (when (and (= true (:register-system-fonts? doc-meta))
             (nil? @fonts-registered?))
    ; register fonts in usual directories
    (FontFactory/registerDirectories)
    (reset! fonts-registered? true)))

(defn- write-doc
  "(write-doc document out)
  document consists of a vector containing a map which defines the document metadata and the contents of the document
  out can either be a string which will be treated as a filename or an output stream"
  [[doc-meta & content] out]
  (register-fonts doc-meta)
  (let [[doc width height temp-stream output-stream pdf-writer] (setup-doc doc-meta out)]
    (doseq [item content]
      (add-item item doc-meta width height doc pdf-writer))
    (.close doc)
    (when (and (not (:pages doc-meta)) (not (empty? (:page-events doc-meta)))) (write-pages temp-stream output-stream))
    (when (:pages doc-meta) (write-total-pages doc width doc-meta temp-stream output-stream))))

(defn- to-pdf [input-reader r out]
  (let [doc-meta (input-reader r)
        [doc width height temp-stream output-stream pdf-writer] (setup-doc doc-meta out)]
    (register-fonts doc-meta)
    (loop []
      (if-let [item (input-reader r)]
        (do
          (add-item item doc-meta width height doc pdf-writer)
          (recur))
        (do
          (.close doc)
          (when (:pages doc-meta) (write-total-pages doc width doc-meta temp-stream output-stream)))))))

(defn- seq-to-doc [items out]
  (let [doc-meta (first items)
        [doc width height temp-stream output-stream pdf-writer] (setup-doc doc-meta out)]
    (register-fonts doc-meta)
    (doseq [item (rest items)]
      (add-item item doc-meta width height doc pdf-writer))
    (.close doc)
    (when (:pages doc-meta) (write-total-pages doc width doc-meta temp-stream output-stream))))

(defn- stream-doc
  "reads the document from an input stream one form at a time and writes it out to the output stream
   NOTE: setting the :pages to true in doc meta will require the entire document to remain in memory for
         post processing!"
  [in out]
  (with-open [r (new PushbackReader (new InputStreamReader in))]
    (binding [*read-eval* false]
      (to-pdf (fn [r] (read r nil nil)) r out))))

(defn pdf
  "usage:
   in can be either a vector containing the document or an input stream. If in is an input stream then the forms will be read sequentially from it.
   out can be either a string, in which case it's treated as a file name, or an output stream.
   NOTE: using the :pages option will cause the complete document to reside in memory as it will need to be post processed."
  [in out]
  (binding [*cache* (atom {})]
    (cond (instance? InputStream in) (stream-doc in out)
          (seq? in) (seq-to-doc in out)
          :else (write-doc in out))))

;;;templating
(defmacro template [t]
 `(fn [~'items]
    (for [~'item ~'items]
       ~(clojure.walk/prewalk
          (fn [x#]
            (if (and (symbol? x#) (.startsWith (name x#) "$"))
              `(~(keyword (.substring (name x#) 1)) ~'item)
              x#))
          t))))
