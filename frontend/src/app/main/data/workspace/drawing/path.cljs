;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.data.workspace.drawing.path
  (:require
   [beicon.core :as rx]
   [potok.core :as ptk]
   [app.common.math :as mth]
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.util.data :as ud]
   [app.common.data :as cd]
   [app.util.geom.path :as ugp]
   [app.main.streams :as ms]
   [app.main.data.workspace.common :as dwc]
   [app.main.data.workspace.drawing.common :as common]
   [app.common.geom.shapes.path :as gsp]))

;; CONSTANTS
(defonce enter-keycode 13)


;; PRIVATE METHODS

(defn get-path-id
  "Retrieves the currently editing path id"
  [state]
  (or (get-in state [:workspace-local :edition])
      (get-in state [:workspace-drawing :object :id])))

(defn get-path
  "Retrieves the location of the path object and additionaly can pass
  the arguments. This location can be used in get-in, assoc-in... functions"
  [state & path]
  (let [edit-id (get-in state [:workspace-local :edition])
        page-id (:current-page-id state)]
    (cd/concat
     (if edit-id
       [:workspace-data :pages-index page-id :objects edit-id]
       [:workspace-drawing :object])
     path)))

(defn update-selrect
  "Updates the selrect and points for a path"
  [shape]
  (let [selrect (gsh/content->selrect (:content shape))
        points (gsh/rect->points selrect)]
    (assoc shape :points points :selrect selrect)))

(defn next-node
  "Calculates the next-node to be inserted."
  [shape position prev-point prev-handler]
  (let [last-command (-> shape :content last :command)
        add-line?   (and prev-point (not prev-handler) (not= last-command :close-path))
        add-curve?  (and prev-point prev-handler (not= last-command :close-path))]
    (cond
      add-line?   {:command :line-to
                   :params position}
      add-curve?  {:command :curve-to
                   :params (ugp/make-curve-params position prev-handler)}
      :else       {:command :move-to
                   :params position})))

(defn append-node
  "Creates a new node in the path. Usualy used when drawing."
  [shape position prev-point prev-handler]
  (let [command (next-node shape position prev-point prev-handler)]
    (-> shape
        (update :content (fnil conj []) command)
        (update-selrect))))

(defn move-handler-modifiers [content index prefix match-opposite? dx dy]
  (let [[cx cy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])
        [ocx ocy] (if (= prefix :c1) [:c2x :c2y] [:c1x :c1y])
        opposite-index (ugp/opposite-index content index prefix)]

    (cond-> {}
      :always
      (update index assoc cx dx cy dy)

      (and match-opposite? opposite-index)
      (update opposite-index assoc ocx (- dx) ocy (- dy)))))

(defn end-path-event? [{:keys [type shift] :as event}]
  (or (= event ::end-path)
      (= (ptk/type event) :esc-pressed)
      (= event :interrupt) ;; ESC
      (and (ms/keyboard-event? event)
           (= type :down)
           ;; TODO: Enter now finish path but can finish drawing/editing as well
           (= enter-keycode (:key event)))))


;; EVENTS

(defn init-path [id]
  (ptk/reify ::init-path))

(defn finish-path [id]
  (ptk/reify ::finish-path
    ptk/UpdateEvent
    (update [_ state]
      (-> state
          (update-in [:workspace-local :edit-path id] dissoc :last-point :prev-handler :drag-handler :preview)))))

(defn preview-next-point [{:keys [x y]}]
  (ptk/reify ::preview-next-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)
            position (gpt/point x y)
            shape (get-in state (get-path state))
            {:keys [last-point prev-handler]} (get-in state [:workspace-local :edit-path id])
            
            command (next-node shape position last-point prev-handler)]
        (assoc-in state [:workspace-local :edit-path id :preview] command)))))

(defn add-node [{:keys [x y]}]
  (ptk/reify ::add-node
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)
            position (gpt/point x y)
            {:keys [last-point prev-handler]} (get-in state [:workspace-local :edit-path id])]
        (-> state
            (assoc-in  [:workspace-local :edit-path id :last-point] position)
            (update-in [:workspace-local :edit-path id] dissoc :prev-handler)
            (update-in (get-path state) append-node position last-point prev-handler))))))

(defn start-drag-handler []
  (ptk/reify ::start-drag-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [content (get-in state (get-path state :content))
            index (dec (count content))
            command (get-in state (get-path state :content index :command))

            make-curve
            (fn [command]
              (let [params (ugp/make-curve-params
                            (get-in content [index :params])
                            (get-in content [(dec index) :params]))]
                (-> command
                    (assoc :command :curve-to :params params))))]

        (cond-> state
          (= command :line-to)
          (update-in (get-path state :content index) make-curve))))))

(defn drag-handler [{:keys [x y]}]
  (ptk/reify ::drag-handler
    ptk/UpdateEvent
    (update [_ state]

      (let [id (get-path-id state)
            handler-position (gpt/point x y)
            shape (get-in state (get-path state))
            content (:content shape)
            index (dec (count content))
            node-position (ugp/command->point (nth content index))
            {dx :x dy :y} (gpt/subtract handler-position node-position)
            match-opposite? true
            modifiers (move-handler-modifiers content (inc index) :c1 match-opposite? dx dy)]
        (-> state
            (assoc-in [:workspace-local :edit-path id :content-modifiers] modifiers)
            (assoc-in [:workspace-local :edit-path id :prev-handler] handler-position)
            (assoc-in [:workspace-local :edit-path id :drag-handler] handler-position))))))

(defn finish-drag []
  (ptk/reify ::finish-drag
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)
            modifiers (get-in state [:workspace-local :edit-path id :content-modifiers])
            handler (get-in state [:workspace-local :edit-path id :drag-handler])]
        (-> state
            (update-in (get-path state :content) ugp/apply-content-modifiers modifiers)
            (update-in [:workspace-local :edit-path id] dissoc :drag-handler)
            (update-in [:workspace-local :edit-path id] dissoc :content-modifiers)
            (assoc-in  [:workspace-local :edit-path id :prev-handler] handler)
            (update-in (get-path state) update-selrect))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-path-id state)
            handler (get-in state [:workspace-local :edit-path id :prev-handler])]
        ;; Update the preview because can be outdated after the dragging
        (rx/of (preview-next-point handler))))))

(defn close-path [position]
  (ptk/reify ::close-path
    ptk/WatchEvent
    (watch [_ state stream]
      (rx/of (add-node position)
             ::end-path))))

(defn close-path-drag-start [position]
  (ptk/reify ::close-path-drag-start
    ptk/WatchEvent
    (watch [_ state stream]
      (let [zoom (get-in state [:workspace-local :zoom])
            threshold (/ 5 zoom)
            check-if-dragging
            (fn [current-position]
              (let [start   (gpt/point position)
                    current (gpt/point current-position)]
                (>= (gpt/distance start current) 100)))

            stop-stream
            (->> stream (rx/filter #(or (end-path-event? %)
                                        (ms/mouse-up? %))))

            position-stream
            (->> ms/mouse-position
                 (rx/take-until stop-stream)
                 (rx/throttle 50))

            drag-events-stream
            (->> position-stream
                 (rx/map #(drag-handler %)))]


        (rx/concat
         (rx/of (close-path position))

         (->> position-stream
              (rx/filter check-if-dragging)
              (rx/take 1)
              (rx/merge-map
               #(rx/concat
                 (rx/of (start-drag-handler))
                 drag-events-stream
                 (rx/of (finish-drag))))))))))

(defn close-path-drag-end [position]
  (ptk/reify ::close-path-drag-end))

(defn path-pointer-enter [position]
  (ptk/reify ::path-pointer-enter))

(defn path-pointer-leave [position]
  (ptk/reify ::path-pointer-leave))

(defn start-path-from-point [position]
  (ptk/reify ::start-path-from-point
    ptk/WatchEvent
    (watch [_ state stream]
      (let [mouse-up    (->> stream (rx/filter #(or (end-path-event? %)
                                                    (ms/mouse-up? %))))
            drag-events (->> ms/mouse-position
                             (rx/take-until mouse-up)
                             (rx/map #(drag-handler %)))]

        (rx/concat (rx/of (add-node position))
                   (rx/of (start-drag-handler))
                   drag-events
                   (rx/of (finish-drag))))
      )))

;; EVENT STREAMS

(defn make-click-stream
  [stream down-event]
  (->> stream
       (rx/filter ms/mouse-click?)
       (rx/debounce 200)
       (rx/first)
       (rx/map #(add-node down-event))))

(defn make-drag-stream
  [stream down-event]
  (let [mouse-up    (->> stream (rx/filter #(or (end-path-event? %)
                                                (ms/mouse-up? %))))
        drag-events (->> ms/mouse-position
                         (rx/take-until mouse-up)
                         (rx/map #(drag-handler %)))]
    (->> (rx/timer 400)
         (rx/merge-map #(rx/concat
                         (rx/of (add-node down-event))
                         (rx/of (start-drag-handler))
                         drag-events
                         (rx/of (finish-drag)))))))

(defn make-dbl-click-stream
  [stream down-event]
  (->> stream
       (rx/filter ms/mouse-double-click?)
       (rx/first)
       (rx/merge-map
        #(rx/of (add-node down-event)
                ::end-path))))

(defn make-node-events-stream
  [stream]
  (->> (rx/merge
        (->> stream (rx/filter (ptk/type? ::close-path)))
        (->> stream (rx/filter (ptk/type? ::close-path-drag-start))))
       (rx/take 1)
       (rx/merge-map #(rx/empty))))

;; MAIN ENTRIES

(defn handle-drawing-path
  [id]
  (ptk/reify ::handle-drawing-path
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)]
        (-> state
            (assoc-in [:workspace-local :edit-path id :edit-mode] :draw))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [mouse-down    (->> stream (rx/filter ms/mouse-down?))
            end-path-events (->> stream (rx/filter end-path-event?))

            ;; Mouse move preview
            mousemove-events
            (->> ms/mouse-position
                 (rx/take-until end-path-events)
                 (rx/throttle 50)
                 (rx/map #(preview-next-point %)))

            ;; From mouse down we can have: click, drag and double click
            mousedown-events
            (->> mouse-down
                 (rx/take-until end-path-events)
                 (rx/throttle 50)
                 (rx/with-latest merge ms/mouse-position)

                 ;; We change to the stream that emits the first event
                 (rx/switch-map
                  #(rx/race (make-node-events-stream stream)
                            (make-click-stream stream %)
                            (make-drag-stream stream %)
                            (make-dbl-click-stream stream %))))]

        (rx/concat
         (rx/of (init-path id))
         (rx/merge mousemove-events
                   mousedown-events)
         (rx/of (finish-path id)))))))

(defn stop-path-edit []
  (ptk/reify ::stop-path-edit
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (update state :workspace-local dissoc :edit-path id)))))

(defn start-path-edit
  [id]
  (ptk/reify ::start-path-edit
    ptk/UpdateEvent
    (update [_ state]
      ;; Only edit if the object has been created
      (if-let [id (get-in state [:workspace-local :edition])]
        (assoc-in state [:workspace-local :edit-path id] {:edit-mode :move
                                                          :selected #{}
                                                          :snap-toggled true})
        state))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> stream
           (rx/filter #(= % :interrupt))
           (rx/take 1)
           (rx/map #(stop-path-edit))))))

(defn modify-point [index prefix dx dy]
  (ptk/reify ::modify-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])
            [cx cy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])]
        (-> state
            (update-in [:workspace-local :edit-path id :content-modifiers (inc index)] assoc
                       :c1x dx :c1y dy)
            (update-in [:workspace-local :edit-path id :content-modifiers index] assoc
                       :x dx :y dy :c2x dx :c2y dy)
            )))))

(defn modify-handler [id index prefix dx dy match-opposite?]
  (ptk/reify ::modify-point
    ptk/UpdateEvent
    (update [_ state]
      (let [content (get-in state (get-path state :content))
            [cx cy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])
            [ocx ocy] (if (= prefix :c1) [:c2x :c2y] [:c1x :c1y])
            opposite-index (ugp/opposite-index content index prefix)]
        (cond-> state
          :always
          (update-in [:workspace-local :edit-path id :content-modifiers index] assoc
                     cx dx cy dy)

          (and match-opposite? opposite-index)
          (update-in [:workspace-local :edit-path id :content-modifiers opposite-index] assoc
                     ocx (- dx) ocy (- dy)))))))

(defn apply-content-modifiers []
  (ptk/reify ::apply-content-modifiers
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-in state [:workspace-local :edition])
            page-id (:current-page-id state)
            shape (get-in state [:workspace-data :pages-index page-id :objects id])
            {old-content :content old-selrect :selrect old-points :points} shape
            content-modifiers (get-in state [:workspace-local :edit-path id :content-modifiers] {})
            new-content (ugp/apply-content-modifiers old-content content-modifiers)
            new-selrect (gsh/content->selrect new-content)
            new-points (gsh/rect->points new-selrect)

            rch [{:type :mod-obj
                  :id id
                  :page-id page-id
                  :operations [{:type :set :attr :content :val new-content}
                               {:type :set :attr :selrect :val new-selrect}
                               {:type :set :attr :points  :val new-points}]}
                 {:type :reg-objects
                  :page-id page-id
                  :shapes [id]}]

            uch [{:type :mod-obj
                  :id id
                  :page-id page-id
                  :operations [{:type :set :attr :content :val old-content}
                               {:type :set :attr :selrect :val old-selrect}
                               {:type :set :attr :points  :val old-points}]}
                 {:type :reg-objects
                  :page-id page-id
                  :shapes [id]}]]

        (rx/of (dwc/commit-changes rch uch {:commit-local? true})
               (fn [state] (update-in state [:workspace-local :edit-path id] dissoc :content-modifiers)))))))

(defn save-path-content []
  (ptk/reify ::save-path-content
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-in state [:workspace-local :edition])
            page-id (:current-page-id state)
            old-content (get-in state [:workspace-local :edit-path id :old-content])
            old-selrect (gsh/content->selrect old-content)
            old-points  (gsh/rect->points old-content)
            shape (get-in state [:workspace-data :pages-index page-id :objects id])
            {new-content :content new-selrect :selrect new-points :points} shape

            rch [{:type :mod-obj
                  :id id
                  :page-id page-id
                  :operations [{:type :set :attr :content :val new-content}
                               {:type :set :attr :selrect :val new-selrect}
                               {:type :set :attr :points  :val new-points}]}
                 {:type :reg-objects
                  :page-id page-id
                  :shapes [id]}]

            uch [{:type :mod-obj
                  :id id
                  :page-id page-id
                  :operations [{:type :set :attr :content :val old-content}
                               {:type :set :attr :selrect :val old-selrect}
                               {:type :set :attr :points  :val old-points}]}
                 {:type :reg-objects
                  :page-id page-id
                  :shapes [id]}]]

        (rx/of (dwc/commit-changes rch uch {:commit-local? true}))))))

(declare start-draw-mode)
(defn check-changed-content []
  (ptk/reify ::check-changed-content
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-path-id state)
            content (get-in state (get-path state :content))
            old-content (get-in state [:workspace-local :edit-path id :old-content])
            mode (get-in state [:workspace-local :edit-path id :edit-mode])]

        (cond
          (not= content old-content) (rx/of (save-path-content)
                                         (start-draw-mode))
          (= mode :draw) (rx/of :interrupt)
          :else (rx/of (finish-path id)))))))

(defn move-path-point [start-point end-point]
  (ptk/reify ::move-point
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-path-id state)
            content (get-in state (get-path state :content))

            {dx :x dy :y} (gpt/subtract end-point start-point)

            handler-indices (-> (ugp/content->handlers content)
                                (get start-point))

            command-for-point (fn [[index command]]
                                (let [point (ugp/command->point command)]
                                  (= point start-point)))

            point-indices (->> (d/enumerate content)
                               (filter command-for-point)
                               (map first))


            point-reducer (fn [modifiers index]
                            (-> modifiers
                                (assoc-in [index :x] dx)
                                (assoc-in [index :y] dy)))

            handler-reducer (fn [modifiers [index prefix]]
                              (let [cx (ud/prefix-keyword prefix :x)
                                    cy (ud/prefix-keyword prefix :y)]
                                (-> modifiers
                                    (assoc-in [index cx] dx)
                                    (assoc-in [index cy] dy))))

            modifiers (as-> (get-in state [:workspace-local :edit-path id :content-modifiers] {}) $
                        (reduce point-reducer $ point-indices)
                        (reduce handler-reducer $ handler-indices))]

        (assoc-in state [:workspace-local :edit-path id :content-modifiers] modifiers)))))

(defn start-move-path-point
  [position]
  (ptk/reify ::start-move-path-point
    ptk/WatchEvent
    ;; TODO REWRITE
    (watch [_ state stream]
      (let [stopper (->> stream (rx/filter ms/mouse-up?))]
        (rx/concat
         (->> ms/mouse-position
              (rx/take-until stopper)
              (rx/map #(move-path-point position %)))
         (rx/of (apply-content-modifiers)))))))

(defn start-move-handler
  [index prefix]
  (ptk/reify ::start-move-handler
    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-in state [:workspace-local :edition])
            [cx cy] (if (= prefix :c1) [:c1x :c1y] [:c2x :c2y])
            start-point @ms/mouse-position
            start-delta-x (get-in state [:workspace-local :edit-path id :content-modifiers index cx] 0)
            start-delta-y (get-in state [:workspace-local :edit-path id :content-modifiers index cy] 0)]

        (rx/concat
         (->> ms/mouse-position
              (rx/take-until (->> stream (rx/filter ms/mouse-up?)))
              (rx/with-latest vector ms/mouse-position-alt)
              (rx/map
               (fn [[pos alt?]]
                 (modify-handler
                  id
                  index
                  prefix
                  (+ start-delta-x (- (:x pos) (:x start-point)))
                  (+ start-delta-y (- (:y pos) (:y start-point)))
                  (not alt?))))
              )
         (rx/concat (rx/of (apply-content-modifiers))))))))

(defn start-draw-mode []
  (ptk/reify ::start-draw-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])
            page-id (:current-page-id state)
            old-content (get-in state [:workspace-data :pages-index page-id :objects id :content])]
        (-> state
            (assoc-in [:workspace-local :edit-path id :old-content] old-content))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-in state [:workspace-local :edition])
            edit-mode (get-in state [:workspace-local :edit-path id :edit-mode])]
        (if (= :draw edit-mode)
          (rx/concat
           (rx/of (handle-drawing-path id))
           (->> stream
                (rx/filter (ptk/type? ::finish-path))
                (rx/take 1)
                (rx/merge-map #(rx/of (check-changed-content)))))
          (rx/empty))))))

(defn change-edit-mode [mode]
  (ptk/reify ::change-edit-mode
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (cond-> state
          id (assoc-in [:workspace-local :edit-path id :edit-mode] mode))))

    ptk/WatchEvent
    (watch [_ state stream]
      (let [id (get-path-id state)]
        (cond
          (and id (= :move mode)) (rx/of ::end-path)
          (and id (= :draw mode)) (rx/of (start-draw-mode))
          :else (rx/empty))))))

(defn select-handler [index type]
  (ptk/reify ::select-handler
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (-> state
            (update-in [:workspace-local :edit-path id :selected] (fnil conj #{}) [index type]))))))

(defn select-node [position]
  (ptk/reify ::select-node
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (-> state
            (update-in [:workspace-local :edit-path id :selected-node] (fnil conj #{}) position))))))

(defn deselect-node [position]
  (ptk/reify ::deselect-node
    ptk/UpdateEvent
    (update [_ state]
      (let [id (get-in state [:workspace-local :edition])]
        (-> state
            (update-in [:workspace-local :edit-path id :selected-node] (fnil disj #{}) position))))))

(defn add-to-selection-handler [index type]
  (ptk/reify ::add-to-selection-handler
    ptk/UpdateEvent
    (update [_ state]
      state)))

(defn add-to-selection-node [index]
  (ptk/reify ::add-to-selection-node
    ptk/UpdateEvent
    (update [_ state]
      state)))

(defn remove-from-selection-handler [index]
  (ptk/reify ::remove-from-selection-handler
    ptk/UpdateEvent
    (update [_ state]
      state)))

(defn remove-from-selection-node [index]
  (ptk/reify ::remove-from-selection-handler
    ptk/UpdateEvent
    (update [_ state]
      state)))

(defn handle-new-shape-result [shape-id]
  (ptk/reify ::handle-new-shape-result
    ptk/UpdateEvent
    (update [_ state]
      (let [content (get-in state [:workspace-drawing :object :content] [])]
        (if (> (count content) 1)
          (assoc-in state [:workspace-drawing :object :initialized?] true)
          state)))

    ptk/WatchEvent
    (watch [_ state stream]
      (->> (rx/of common/handle-finish-drawing
                  (dwc/start-edition-mode shape-id)
                  (start-path-edit shape-id)
                  (change-edit-mode :draw))))))

(defn handle-new-shape
  "Creates a new path shape"
  []
  (ptk/reify ::handle-new-shape
    ptk/WatchEvent
    (watch [_ state stream]
      (let [shape-id (get-in state [:workspace-drawing :object :id])]
        (rx/concat
         (rx/of (handle-drawing-path shape-id))
         (->> stream
              (rx/filter (ptk/type? ::finish-path))
              (rx/take 1)
              (rx/observe-on :async)
              (rx/map #(handle-new-shape-result shape-id))))))))