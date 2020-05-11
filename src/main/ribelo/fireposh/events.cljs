(ns ribelo.fireposh.events
  (:require
   [day8.re-frame.async-flow-fx]
   [re-frame.core :as rf]
   [re-posh.core :as rp]
   [datascript.core :as d]
   [ribelo.firenze.firebase :as fb]
   [ribelo.fireposh.fx :as fx]
   [ribelo.firenze.realtime-database :as rdb]
   [applied-science.js-interop :as j]
   [cljs-bean.core :refer [bean ->js ->clj]]))

(rf/reg-event-fx
 ::init-firebase
 (fn [_ [_ app-info]]
   {::fx/init-firebase app-info}))

(rf/reg-event-fx
 ::create-connection.firebase-schema
 (fn [_ _]
   {::fx/create-connection.firebase-schema nil}))

(rf/reg-event-fx
 ::create-connection.local-schema
 (fn [_ [_ schema]]
   {::fx/create-connection.local-schema schema}))

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
 ::create-transactor
 (fn [_ [_ size wait]]
   {::fx/create-transactor (or size 1024) (or wait 1000)}))

(rf/reg-event-fx
 :transact!
 (fn [_ [_ tx-data]]
   {:transact tx-data}))

(rf/reg-event-fx
 ::init-successful
 (fn [_ _]))

(rf/reg-event-fx
 ::init
 (fn [_ [_ app-info ?db]]
   {:async-flow
    {:first-dispatch [::init-firebase app-info]
     :rules          (if-not ?db
                       [{:when :seen? :events [::init-firebase] :dispatch [::create-connection.firebase-schema]}
                        {:when :seen? :events [::create-connection.local-schema] :dispatch [::link-db]}
                        {:when :seen? :events [::link-db] :dispatch [::init-successful]}]
                       [{:when :seen? :events [::init-firebase] :dispatch [::create-connection.from-db ?db]}
                        {:when :seen? :events [::create-connection.from-db] :dispatch [::link-db]}
                        {:when :seen? :events [::link-db] :dispatch [::init-successful]}])}}))
