;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020-2021 UXBOX Labs SL

(ns app.http
  (:require
   [app.common.spec :as us]
   [app.common.data :as d]
   [app.config :as cfg]
   [app.http.auth :as auth]
   [app.http.errors :as errors]
   [app.http.middleware :as middleware]
   [app.http.assets :as assets]
   [app.metrics :as mtx]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [clojure.spec.alpha :as s]
   [reitit.ring :as rr]
   [ring.adapter.jetty9 :as jetty])
  (:import
   org.eclipse.jetty.server.handler.ErrorHandler))

(s/def ::handler fn?)
(s/def ::ws (s/map-of ::us/string fn?))
(s/def ::port ::cfg/http-server-port)
(s/def ::name ::us/string)

(defmethod ig/pre-init-spec ::server [_]
  (s/keys :req-un [::handler ::port]
          :opt-un [::ws ::name]))

(defmethod ig/prep-key ::server
  [_ cfg]
  (merge {:name "http"}
         (d/without-nils cfg)))

(defmethod ig/init-key ::server
  [_ {:keys [handler ws port name] :as opts}]
  (log/infof "Starting %s server on port %s." name port)
  (let [options (merge
                 {:port port
                  :h2c? true
                  :join? false
                  :allow-null-path-info true}
                 (when (seq ws)
                   {:websockets ws}))
        server  (jetty/run-jetty handler options)
        handler (doto (ErrorHandler.)
                  (.setShowStacks true)
                  (.setServer server))]

    (.setErrorHandler server handler)

    (assoc opts :server server)))

(defmethod ig/halt-key! ::server
  [_ {:keys [server name port] :as opts}]
  (log/infof "Stoping %s server on port %s." name port)
  (.stop server))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Http Main Handler (Router)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare create-router)

(s/def ::rpc map?)
(s/def ::session map?)
(s/def ::metrics map?)
(s/def ::google-auth map?)
(s/def ::gitlab-auth map?)
(s/def ::ldap-auth fn?)
(s/def ::storage map?)

(defmethod ig/pre-init-spec ::router [_]
  (s/keys :req-un [::rpc ::session ::metrics ::google-auth ::gitlab-auth ::storage]))

(defmethod ig/init-key ::router
  [_ cfg]
  (rr/ring-handler
   (create-router cfg)
   (rr/routes
    (rr/create-resource-handler {:path "/"})
    (rr/create-default-handler))))


(defn- create-router
  [{:keys [session rpc google-auth gitlab-auth github-auth metrics ldap-auth storage svgparse] :as cfg}]
  (rr/router
   [["/metrics" {:get (:handler metrics)}]

    ["/assets" {:middleware [[middleware/format-response-body]
                             [middleware/errors errors/handle]]}
     ["/by-id/:id" {:get #(assets/objects-handler storage %)}]
     ["/by-file-media-id/:id" {:get #(assets/file-objects-handler storage %)}]
     ["/by-file-media-id/:id/thumbnail" {:get #(assets/file-thumbnails-handler storage %)}]]

    ["/api" {:middleware [[middleware/format-response-body]
                          [middleware/parse-request-body]
                          [middleware/errors errors/handle]
                          [middleware/params]
                          [middleware/multipart-params]
                          [middleware/keyword-params]
                          [middleware/cookies]]}

     ["/svg" {:post svgparse}]

     ["/oauth"
      ["/google" {:post (:auth-handler google-auth)}]
      ["/google/callback" {:get (:callback-handler google-auth)}]

      ["/gitlab" {:post (:auth-handler gitlab-auth)}]
      ["/gitlab/callback" {:get (:callback-handler gitlab-auth)}]

      ["/github" {:post (:auth-handler github-auth)}]
      ["/github/callback" {:get (:callback-handler github-auth)}]]

     ["/login" {:post #(auth/login-handler cfg %)}]
     ["/logout" {:post #(auth/logout-handler cfg %)}]

     ["/login-ldap" {:post ldap-auth}]

     ["/rpc" {:middleware [(:middleware session)]}
      ["/query/:type" {:get (:query-handler rpc)}]
      ["/mutation/:type" {:post (:mutation-handler rpc)}]]]]))
