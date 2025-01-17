; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.viewport
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.uuid :as uuid]
   [app.main.constants :as c]
   [app.main.data.colors :as dwc]
   [app.main.data.fetch :as mdf]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.drawing :as dd]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.streams :as ms]
   [app.main.ui.context :as ctx]
   [app.main.ui.cursors :as cur]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as i]
   [app.main.ui.keyboard :as kbd]
   [app.main.ui.workspace.colorpicker.pixel-overlay :refer [pixel-overlay]]
   [app.main.ui.workspace.comments :refer [comments-layer]]
   [app.main.ui.workspace.drawarea :refer [draw-area]]
   [app.main.ui.workspace.frame-grid :refer [frame-grid]]
   [app.main.ui.workspace.gradients :refer [gradient-handlers]]
   [app.main.ui.workspace.presence :as presence]
   [app.main.ui.workspace.selection :refer [selection-handlers]]
   [app.main.ui.workspace.shapes :refer [shape-wrapper frame-wrapper]]
   [app.main.ui.workspace.shapes.interactions :refer [interactions]]
   [app.main.ui.workspace.shapes.outline :refer [outline]]
   [app.main.ui.workspace.snap-distances :refer [snap-distances]]
   [app.main.ui.workspace.snap-points :refer [snap-points]]
   [app.util.dom :as dom]
   [app.util.dom.dnd :as dnd]
   [app.util.object :as obj]
   [app.util.perf :as perf]
   [app.util.timers :as timers]
   [app.util.http :as http]
   [beicon.core :as rx]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [goog.events :as events]
   [potok.core :as ptk]
   [promesa.core :as p]
   [rumext.alpha :as mf]
   [app.main.ui.workspace.shapes.path.actions :refer [path-actions]])
  (:import goog.events.EventType))

;; --- Coordinates Widget

(mf/defc coordinates
  []
  (let [coords (some-> (hooks/use-rxsub ms/mouse-position)
                       (gpt/round))]
    [:ul.coordinates
     [:span {:alt "x"}
      (str "X: " (:x coords "-"))]
     [:span {:alt "y"}
      (str "Y: " (:y coords "-"))]]))

(mf/defc cursor-tooltip
  [{:keys [zoom tooltip] :as props}]
  (let [coords (some-> (hooks/use-rxsub ms/mouse-position)
                       (gpt/divide (gpt/point zoom zoom)))
        pos-x (- (:x coords) 100)
        pos-y (+ (:y coords) 30)]
    [:g {:transform (str "translate(" pos-x "," pos-y ")")}
     [:foreignObject {:width 200 :height 100 :style {:text-align "center"}}
      [:span tooltip]]]))

;; --- Cursor tooltip

(defn- get-shape-tooltip
  "Return the shape tooltip text"
  [shape]
  (case (:type shape)
    :icon "Click to place the Icon"
    :image "Click to place the Image"
    :rect "Drag to draw a Box"
    :text "Drag to draw a Text Box"
    :path "Click to draw a Path"
    :circle "Drag to draw a Circle"
    nil))

;; --- Selection Rect

(mf/defc selection-rect
  {:wrap [mf/memo]}
  [{:keys [data] :as props}]
  (when data
    [:rect.selection-rect
     {:x (:x data)
      :y (:y data)
      :width (:width data)
      :height (:height data)}]))

;; --- Viewport Positioning

(defn- handle-viewport-positioning
  [viewport-ref]
  (let [node   (mf/ref-val viewport-ref)
        stoper (rx/filter #(= ::finish-positioning %) st/stream)

        stream (->> ms/mouse-position-delta
                    (rx/take-until stoper))]
    (st/emit! dw/start-pan)
    (rx/subscribe stream
                  (fn [delta]
                    (let [zoom (gpt/point @refs/selected-zoom)
                          delta (gpt/divide delta zoom)]
                      (st/emit! (dw/update-viewport-position
                                 {:x #(- % (:x delta))
                                  :y #(- % (:y delta))})))))))

;; --- Viewport

(declare remote-user-cursors)


;; TODO: revisit the refs usage (vs props)
(mf/defc shape-outlines
  {::mf/wrap-props false}
  [props]
  (let [objects   (unchecked-get props "objects")
        selected  (or (unchecked-get props "selected") #{})
        hover     (or (unchecked-get props "hover") #{})
        edition   (unchecked-get props "edition")
        outline?  (set/union selected hover)
        show-outline? (fn [shape] (and (not (:hidden shape))
                                       (not (:blocked shape))
                                       (not= edition (:id shape))
                                       (outline? (:id shape))))
        shapes    (->> (vals objects) (filter show-outline?))
        transform (mf/deref refs/current-transform)
        color (if (or (> (count shapes) 1) (nil? (:shape-ref (first shapes))))
                "#31EFB8"
                "#00E0FF")]
    (when (nil? transform)
      [:g.outlines
       (for [shape shapes]
         [:& outline {:key (str "outline-" (:id shape))
                      :shape (gsh/transform-shape shape)
                      :color color}])])))

(mf/defc frames
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]
  (let [hover    (unchecked-get props "hover")
        selected (unchecked-get props "selected")
        ids      (unchecked-get props "ids")
        ghost?   (unchecked-get props "ghost?")
        edition  (unchecked-get props "edition")
        data     (mf/deref refs/workspace-page)
        objects  (:objects data)
        root     (get objects uuid/zero)
        shapes   (->> (:shapes root)
                      (map #(get objects %)))

        shapes (if ids
                 (->> ids (map #(get objects %)))
                 shapes)]

    [:*
     [:g.shapes
      (for [item shapes]
        (if (= (:type item) :frame)
          [:& frame-wrapper {:shape item
                             :key (:id item)
                             :objects objects
                             :ghost? ghost?}]
          [:& shape-wrapper {:shape item
                             :key (:id item)
                             :ghost? ghost?}]))]

     (when (not ghost?)
       [:& shape-outlines {:objects objects
                           :selected selected
                           :hover hover
                           :edition edition}])]))

(mf/defc ghost-frames
  {::mf/wrap-props false}
  [props]
  (let [modifiers    (obj/get props "modifiers")
        selected     (obj/get props "selected")

        sobjects     (mf/deref refs/selected-objects)
        selrect-orig (gsh/selection-rect sobjects)

        xf           (comp
                      (map #(assoc % :modifiers modifiers))
                      (map gsh/transform-shape))

        selrect      (->> (into [] xf sobjects)
                          (gsh/selection-rect))]
    [:svg.ghost
     {:x (:x selrect)
      :y (:y selrect)
      :width (:width selrect)
      :height (:height selrect)
      :style {:pointer-events "none"}}

     [:g {:transform (str/fmt "translate(%s,%s)" (- (:x selrect-orig)) (- (:y selrect-orig)))}
      [:& frames
       {:ids selected
        :ghost? true}]]]))

(defn format-viewbox [vbox]
  (str/join " " [(+ (:x vbox 0) (:left-offset vbox 0))
                 (:y vbox 0)
                 (:width vbox 0)
                 (:height vbox 0)]))

(mf/defc viewport
  [{:keys [local layout file] :as props}]
  (let [{:keys [options-mode
                zoom
                flags
                vport
                vbox
                edition
                edit-path
                tooltip
                selected
                panning
                picking-color?]} local

        page-id       (mf/use-ctx ctx/current-page-id)

        selected-objects (mf/deref refs/selected-objects)
        selrect-orig     (->> selected-objects
                              (gsh/selection-rect))
        selrect          (->> selected-objects
                              (map #(assoc % :modifiers (:modifiers local)))
                              (map gsh/transform-shape)
                              (gsh/selection-rect))

        alt?          (mf/use-state false)
        viewport-ref  (mf/use-ref nil)
        zoom-view-ref (mf/use-ref nil)
        last-position (mf/use-var nil)
        drawing       (mf/deref refs/workspace-drawing)
        drawing-tool  (:tool drawing)
        drawing-obj   (:object drawing)
        drawing-path? (and edition (= :draw (get-in edit-path [edition :edit-mode])))
        zoom          (or zoom 1)

        on-mouse-down
        (mf/use-callback
         (mf/deps drawing-tool edition)
         (fn [event]
           (dom/stop-propagation event)
           (let [event (.-nativeEvent event)
                 ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)]
             (when (= 1 (.-which event))
               (st/emit! (ms/->MouseEvent :down ctrl? shift? alt?)))

             (cond
               (and (= 1 (.-which event)) (not edition))
               (if drawing-tool
                 (when (not (#{:comments :path} drawing-tool))
                   (st/emit! (dd/start-drawing drawing-tool)))
                 (st/emit! (dw/handle-selection shift?)))

               (and (= 2 (.-which event)))
               (handle-viewport-positioning viewport-ref)))))

        on-context-menu
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (let [position (dom/get-client-position event)]
             (st/emit! (dw/show-context-menu {:position position})))))

        on-mouse-up
        (mf/use-callback
         (fn [event]
           (dom/stop-propagation event)
           (let [event (.-nativeEvent event)
                 ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)]
             (when (= 1 (.-which event))
               (st/emit! (ms/->MouseEvent :up ctrl? shift? alt?)))

             (when (= 2 (.-which event))
               (do
                 (dom/prevent-default event)
                 (st/emit! dw/finish-pan
                           ::finish-positioning))))))

        on-pointer-down
        (mf/use-callback
         (fn [event]
           (let [target (dom/get-target event)]
                                        ; Capture mouse pointer to detect the movements even if cursor
                                        ; leaves the viewport or the browser itself
                                        ; https://developer.mozilla.org/en-US/docs/Web/API/Element/setPointerCapture
             (.setPointerCapture target (.-pointerId event)))))

        on-pointer-up
        (mf/use-callback
         (fn [event]
           (let [target (dom/get-target event)]
                                        ; Release pointer on mouse up
             (.releasePointerCapture target (.-pointerId event)))))

        on-click
        (mf/use-callback
         (fn [event]
           (let [ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)]
             (st/emit! (ms/->MouseEvent :click ctrl? shift? alt?)))))

        on-double-click
        (mf/use-callback
         (mf/deps drawing-path?)
         (fn [event]
           (dom/stop-propagation event)
           (let [ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)]
             (st/emit! (ms/->MouseEvent :double-click ctrl? shift? alt?))

             (if (not drawing-path?)
               (st/emit! dw/clear-edition-mode)))))

        on-key-down
        (mf/use-callback
         (fn [event]
           (let [bevent (.getBrowserEvent ^js event)
                 key (.-keyCode ^js event)
                 ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)
                 target (dom/get-target event)]

             (when-not (.-repeat bevent)
               (st/emit! (ms/->KeyboardEvent :down key ctrl? shift? alt?))
               (when (and (kbd/space? event)
                          (not= "rich-text" (obj/get target "className"))
                          (not= "INPUT" (obj/get target "tagName"))
                          (not= "TEXTAREA" (obj/get target "tagName")))
                 (handle-viewport-positioning viewport-ref))))))

        on-key-up
        (mf/use-callback
         (fn [event]
           (let [key (.-keyCode event)
                 ctrl? (kbd/ctrl? event)
                 shift? (kbd/shift? event)
                 alt? (kbd/alt? event)]
             (when (kbd/space? event)
               (st/emit! dw/finish-pan ::finish-positioning))
             (st/emit! (ms/->KeyboardEvent :up key ctrl? shift? alt?)))))

        translate-point-to-viewport
        (mf/use-callback
         (fn [pt]
           (let [viewport (mf/ref-val viewport-ref)
                 vbox     (.. ^js viewport -viewBox -baseVal)
                 brect    (.getBoundingClientRect viewport)
                 brect    (gpt/point (d/parse-integer (.-left brect))
                                     (d/parse-integer (.-top brect)))
                 box      (gpt/point (.-x vbox)
                                     (.-y vbox))
                 ]
             (-> (gpt/subtract pt brect)
                 (gpt/divide (gpt/point @refs/selected-zoom))
                 (gpt/add box)
                 (gpt/round 0)))))

        on-mouse-move
        (mf/use-callback
         (fn [event]
           (let [event  (.getBrowserEvent ^js event)
                 raw-pt (dom/get-client-position event)
                 pt     (translate-point-to-viewport raw-pt)

                 ;; We calculate the delta because Safari's MouseEvent.movementX/Y drop
                 ;; events
                 delta (if @last-position
                         (gpt/subtract raw-pt @last-position)
                         (gpt/point 0 0))]
             (reset! last-position raw-pt)
             (st/emit! (ms/->PointerEvent :delta delta
                                          (kbd/ctrl? event)
                                          (kbd/shift? event)
                                          (kbd/alt? event)))
             (st/emit! (ms/->PointerEvent :viewport pt
                                          (kbd/ctrl? event)
                                          (kbd/shift? event)
                                          (kbd/alt? event))))))

        on-mouse-wheel
        (mf/use-callback
         (fn [event]
           (let [node (mf/ref-val viewport-ref)
                 target (dom/get-target event)]
             (cond
               (kbd/ctrl? event)
               (let [event (.getBrowserEvent ^js event)
                     pos   @ms/mouse-position]
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (if (pos? (.-deltaY event))
                   (st/emit! (dw/decrease-zoom pos))
                   (st/emit! (dw/increase-zoom pos))))

               (.contains ^js node target)
               (let [event (.getBrowserEvent ^js event)
                     delta (.-deltaY ^js event)
                     delta (/ delta @refs/selected-zoom)]
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (if (kbd/shift? event)
                   (st/emit! (dw/update-viewport-position {:x #(+ % delta)}))
                   (st/emit! (dw/update-viewport-position {:y #(+ % delta)}))))))))

        on-drag-enter
        (mf/use-callback
         (fn [e]
           (when (or (dnd/has-type? e "app/shape")
                     (dnd/has-type? e "app/component")
                     (dnd/has-type? e "Files")
                     (dnd/has-type? e "text/uri-list")
                     (dnd/has-type? e "text/asset-id"))
             (dom/prevent-default e))))

        on-drag-over
        (mf/use-callback
         (fn [e]
           (when (or (dnd/has-type? e "app/shape")
                     (dnd/has-type? e "app/component")
                     (dnd/has-type? e "Files")
                     (dnd/has-type? e "text/uri-list")
                     (dnd/has-type? e "text/asset-id"))
             (dom/prevent-default e))))

        on-uploaded
        (mf/use-callback
         (fn [image {:keys [x y]}]
           (prn "on-uploaded" image x y)
           (let [shape {:name     (:name image)
                        :width    (:width image)
                        :height   (:height image)
                        :x        (- x (/ (:width image) 2))
                        :y        (- y (/ (:height image) 2))
                        :metadata {:width  (:width image)
                                   :height (:height image)
                                   :name   (:name image)
                                   :id     (:id image)
                                   :mtype  (:mtype image)}}]
             (st/emit! (dw/create-and-add-shape :image x y shape)))))

        on-drop
        (mf/use-callback
         (fn [event]
           (dom/prevent-default event)
           (let [point (gpt/point (.-clientX event) (.-clientY event))
                 viewport-coord (translate-point-to-viewport point)]
             (cond
               (dnd/has-type? event "app/shape")
               (let [shape (dnd/get-data event "app/shape")
                     final-x (- (:x viewport-coord) (/ (:width shape) 2))
                     final-y (- (:y viewport-coord) (/ (:height shape) 2))]
                 (st/emit! (dw/add-shape (-> shape
                                             (assoc :id (uuid/next))
                                             (assoc :x final-x)
                                             (assoc :y final-y)))))

               (dnd/has-type? event "app/component")
               (let [{:keys [component file-id]} (dnd/get-data event "app/component")
                     shape (get-in component [:objects (:id component)])
                     final-x (- (:x viewport-coord) (/ (:width shape) 2))
                     final-y (- (:y viewport-coord) (/ (:height shape) 2))]
                 (st/emit! (dwl/instantiate-component file-id
                                                      (:id component)
                                                      (gpt/point final-x final-y))))

               (dnd/has-type? event "text/uri-list")
               (let [data  (dnd/get-data event "text/uri-list")
                     name  (dnd/get-data event "text/asset-name")
                     lines (str/lines data)
                     urls  (filter #(and (not (str/blank? %))
                                         (not (str/starts-with? % "#")))
                                   lines)]
                 (st/emit!
                  (dw/upload-media-objects
                   (with-meta {:file-id (:id file)
                               :local? true
                               :uris urls
                               :name name}
                     {:on-success #(on-uploaded % viewport-coord)}))))

               (dnd/has-type? event "text/asset-id")
               (let [id     (-> (dnd/get-data event "text/asset-id") uuid/uuid)
                     name   (dnd/get-data event "text/asset-name")
                     params {:file-id (:id file)
                             :local? true
                             :object-id id
                             :name name}]
                 (st/emit! (dw/clone-media-object
                            (with-meta params
                              {:on-success #(on-uploaded % viewport-coord)}))))

               :else
               (let [files  (dnd/get-files event)
                     params {:file-id (:id file)
                             :local? true
                             :data (seq files)}]
                 (st/emit! (dw/upload-media-objects
                            (with-meta params
                              {:on-success #(on-uploaded % viewport-coord)}))))))))

        on-paste
        (mf/use-callback
         (fn [event]
           (st/emit! (dw/paste-from-event event))))

        on-resize
        (mf/use-callback
         (fn [event]
           (let [node (mf/ref-val viewport-ref)
                 prnt (dom/get-parent node)
                 size (dom/get-client-size prnt)]
             ;; We schedule the event so it fires after `initialize-page` event
             (timers/schedule #(st/emit! (dw/update-viewport-size size))))))

        options (mf/deref refs/workspace-page-options)]

    (mf/use-layout-effect
     (fn []
       (let [node (mf/ref-val viewport-ref)
             prnt (dom/get-parent node)

             keys [(events/listen js/document EventType.KEYDOWN on-key-down)
                   (events/listen js/document EventType.KEYUP on-key-up)
                   (events/listen node EventType.MOUSEMOVE on-mouse-move)
                   ;; bind with passive=false to allow the event to be cancelled
                   ;; https://stackoverflow.com/a/57582286/3219895
                   (events/listen js/window EventType.WHEEL on-mouse-wheel #js {:passive false})
                   (events/listen js/window EventType.RESIZE on-resize)
                   (events/listen js/window EventType.PASTE on-paste)]]

         (fn []
           (doseq [key keys]
             (events/unlistenByKey key))))))

    (mf/use-layout-effect
     (fn []
       (mf/deps page-id)
       (let [node (mf/ref-val viewport-ref)
             prnt (dom/get-parent node)
             size (dom/get-client-size prnt)]
         ;; We schedule the event so it fires after `initialize-page` event
         (timers/schedule #(st/emit! (dw/initialize-viewport size))))))

    (mf/use-layout-effect (mf/deps layout) on-resize)
    (hooks/use-stream ms/keyboard-alt #(reset! alt? %))

    [:*
     (when picking-color?
       [:& pixel-overlay {:vport vport
                          :vbox vbox
                          :viewport-ref viewport-ref
                          :options options
                          :layout layout}])

     (when (= drawing-tool :comments)
       [:& comments-layer {:vbox (:vbox local)
                           :vport (:vport local)
                           :zoom (:zoom local)
                           :drawing drawing
                           :page-id page-id
                           :file-id (:id file)}])

     [:svg.viewport
      {:preserveAspectRatio "xMidYMid meet"
       :key page-id
       :width (:width vport 0)
       :height (:height vport 0)
       :view-box (format-viewbox vbox)
       :ref viewport-ref
       :class (when drawing-tool "drawing")
       :style {:cursor (cond
                         panning cur/hand
                         (= drawing-tool :comments) cur/comments
                         (= drawing-tool :frame) cur/create-artboard
                         (= drawing-tool :rect) cur/create-rectangle
                         (= drawing-tool :circle) cur/create-ellipse
                         (or (= drawing-tool :path) drawing-path?) cur/pen
                         (= drawing-tool :curve) cur/pencil
                         drawing-tool cur/create-shape
                         :else cur/pointer-inner)
               :background-color (get options :background "#E8E9EA")}
       :on-context-menu on-context-menu
       :on-click on-click
       :on-double-click on-double-click
       :on-mouse-down on-mouse-down
       :on-mouse-up on-mouse-up
       :on-pointer-down on-pointer-down
       :on-pointer-up on-pointer-up
       :on-drag-enter on-drag-enter
       :on-drag-over on-drag-over
       :on-drop on-drop}

      [:g {:style {:pointer-events (if (contains? layout :comments)
                                     "none"
                                     "auto")}}
       [:& frames {:key page-id
                   :hover (:hover local)
                   :selected selected
                   :edition edition}]

       (when (= :move (:transform local))
         [:& ghost-frames {:modifiers (:modifiers local)
                           :selected selected}])

       (when (seq selected)
         [:& selection-handlers {:selected selected
                                 :zoom zoom
                                 :edition edition
                                 :show-distances (and (not (:transform local)) @alt?)}])

       (when (= (count selected) 1)
         [:& gradient-handlers {:id (first selected)
                                :zoom zoom}])

       (when drawing-obj
         [:& draw-area {:shape drawing-obj
                        :zoom zoom
                        :tool drawing-tool
                        :modifiers (:modifiers local)}])

       (when (contains? layout :display-grid)
         [:& frame-grid {:zoom zoom}])

       [:& snap-points {:layout layout
                        :transform (:transform local)
                        :drawing drawing-obj
                        :zoom zoom
                        :page-id page-id
                        :selected selected
                        :local local}]

       [:& snap-distances {:layout layout
                           :zoom zoom
                           :transform (:transform local)
                           :selected selected
                           :page-id page-id}]

       (when tooltip
         [:& cursor-tooltip {:zoom zoom :tooltip tooltip}])]

      [:& presence/active-cursors {:page-id page-id}]
      [:& selection-rect {:data (:selrect local)}]
      (when (= options-mode :prototype)
        [:& interactions {:selected selected}])]]))


(mf/defc viewport-actions []
  (let [edition (mf/deref refs/selected-edition)
        selected (mf/deref refs/selected-objects)
        shape (-> selected first)]
    (when (and (= (count selected) 1)
               (= (:id shape) edition)
               (= :path (:type shape)))
      [:div.viewport-actions
       [:& path-actions {:shape shape}]])))
