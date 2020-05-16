(ns ribelo.fireposh.events
  (:require
   [day8.re-frame.async-flow-fx]
   [re-frame.core :as rf]
   [re-posh.core :as rp]
   [datascript.core :as d]
   [datascript.transit :as dt]
   [ribelo.firenze.firebase :as fb]
   [ribelo.firenze.utils :as fu]
   [ribelo.fireposh.fx :as fx]
   [ribelo.firenze.realtime-database :as rdb]
   [applied-science.js-interop :as j]
   [cljs-bean.core :refer [bean ->js ->clj]]))


(rf/reg-event-fx
 ::init-firebase
 (fn [_ [_ _]]
   {::fx/init-firebase nil}))

(rf/reg-event-fx
 ::create-connection.firebase-schema
 (fn [_ _]
   {::fx/create-connection.firebase-schema nil}))

(rf/reg-event-fx
 ::create-connection.from-schema
 (fn [_ [_ schema]]
   {::fx/create-connection.from-schema schema}))

(rf/reg-event-fx
 ::create-connection.from-db
 (fn [_ [_ db]]
   {::fx/create-connection.from-db db}))

(rf/reg-event-fx
 ::set-schema
 (fn [_ [_ schema]]
   {::fx/set-schema schema}))

(rf/reg-event-fx
 ::link-max-eid
 (fn [_ _]
   {::fx/link-max-eid nil}))

(rf/reg-event-fx
 ::link-db
 (fn [_ [_]]
   {::fx/link-db nil}))

(rf/reg-event-fx
 ::link-path
 (fn [_ [_ path]]
   {::fx/link-paths [path]}))

(rf/reg-event-fx
 ::link-paths
 (fn [_ [_ paths]]
   {::fx/link-paths paths}))

(rf/reg-event-fx
 ::unlink-path
 (fn [_ [_ path]]
   {::fx/unlink-paths [path]}))

(rf/reg-event-fx
 ::unlink-paths
 (fn [_ [_ paths]]
   {::fx/unlink-paths paths}))

(rf/reg-event-fx
 :transact!
 (fn [_ [_ tx-data]]
   {:transact tx-data}))

(comment
  (def app-info {"apiKey"             "AIzaSyB40UqlQQKsvs6FyhQSVOZpZgXl9vYTRa4",
                 "authDomain"         "mannheim-6c4c9.firebaseapp.com",
                 "databaseURL"        "https://mannheim-6c4c9.firebaseio.com",
                 "projectId"          "mannheim-6c4c9",
                 "storageBucket"      "mannheim-6c4c9.appspot.com",
                 "messagingSenderId"  "7595437391",
                 "appId"              "1:7595437391:web:c8b9b7a11cc00c829da93a"})
  (rf/dispatch [::init-firebase app-info])
  (rdb/database)
  (rdb/remove [:_meta])
  (j/call (rdb/ref [:_metas "sd"])))
