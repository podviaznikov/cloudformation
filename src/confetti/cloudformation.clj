(ns confetti.cloudformation
  (:refer-clojure :exclude [ref])
  (:require [confetti.policies :as pol]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [camel-snake-kebab.core :as case]
            [camel-snake-kebab.extras :refer [transform-keys]]
            [amazonica.aws.cloudformation :as cformation]))

(defn validate-creds! [cred]
  (assert (and (string? (:access-key cred))
               (string? (:secret-key cred))) cred))

;; hardcoded value for Cloudfront according to
;; http://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/aws-properties-route53-aliastarget.html
(def cloudfront-hosted-zone-id "Z2FDTNDATAQYW2")

(defn ref [resource]
  {:ref (case/->PascalCaseString resource)})

(defn attr [resource property]
  { "Fn::GetAtt" [(case/->PascalCaseString resource)
                  (case/->PascalCaseString property)]})

(defn join [& args]
  { "Fn::Join" [ "" args]})

;; rid = resource identifier
;; ie. the keys used in the resources map if the cloudfront template

(defn hosted-zone [domain-rid]
  {:type "AWS::Route53::HostedZone"
   :properties {:hosted-zone-tags [{:key "confetti"
                                    :value "site-xyz"}]
                :name (ref domain-rid)}})

(defn zone-record-set [cloudfront-dist-rid hosted-zone-rid domain-rid]
  {:type "AWS::Route53::RecordSet"
   :properties {:alias-target {:d-n-s-name (attr cloudfront-dist-rid :domain-name)
                               :hosted-zone-id cloudfront-hosted-zone-id}
                :hosted-zone-id (ref hosted-zone-rid)
                :name (ref domain-rid)
                :type "A"}})

(defn bucket []
  {:type "AWS::S3::Bucket"
   :properties {:access-control "PublicRead"
                :website-configuration {:error-document "error.html"
                                        :index-document "index.html"}}})

(defn bucket-policy [bucket-rid]
  {:type "AWS::S3::BucketPolicy"
   :properties {:bucket (ref bucket-rid)
                :policy-document (pol/bucket-policy (ref bucket-rid))}})

(defn user-policy [bucket-rid]
  {:policy-name (join (ref bucket-rid) "-S3-BucketFullAccess")
   :policy-document (pol/user-policy (ref bucket-rid))})

(defn user [& policies]
  {:type "AWS::IAM::User"
   :properties {:policies policies}})

(defn access-key [user-rid]
  {:type "AWS::IAM::AccessKey"
   :properties {:status "Active"
                :user-name (ref user-rid)}})

(defn cloudfront-dist [domain-rid bucket-rid]
  {:type "AWS::CloudFront::Distribution"
   :properties {:distribution-config
                {:comment "CDN for S3 backed website"
                 :origins [{:domain-name        (attr bucket-rid :domain-name)
                            :id                 (attr bucket-rid :domain-name)
                            :CustomOriginConfig {:origin-protocol-policy "match-viewer"}}]
                 :default-cache-behavior {:target-origin-id (attr bucket-rid :domain-name)
                                          :forwarded-values {:query-string "false"}
                                          :viewer-protocol-policy "allow-all"}
                 :enabled "true"
                 :default-root-object "index.html"
                 :aliases [(ref domain-rid)]}}})

(defn template [{:keys [dns?] :as opts}]
  {:description "Confetti from Clojure"
   :parameters {:user-domain {:type "String"}}
   :resources (cond-> {:site-bucket            (bucket)
                       :bucket-policy          (bucket-policy :site-bucket)
                       :bucket-user            (user (user-policy :site-bucket))
                       :bucket-user-access-key (access-key :bucket-user)
                       :site-cdn               (cloudfront-dist :user-domain :site-bucket)}
                dns? (assoc :hosted-zone     (hosted-zone :user-domain))
                dns? (assoc :zone-record-set (zone-record-set :site-cdn :hosted-zone :user-domain)))

   :outputs (cond-> {:bucket-name {:value (ref :site-bucket)
                                   :description "Name of the S3 bucket"}
                     :access-key-id {:value (ref :bucket-user-access-key)
                                     :description "AccessKey that can only access bucket"}
                     :site-cdn-url {:value (attr :site-cdn :domain-name)
                                    :description "URL to access CloudFront distribution"}
                     :secret-access-key {:value (attr :bucket-user-access-key :secret-access-key)
                                         :description "Secret for AccessKey that can only access bucket"}}
              dns? (assoc :website-url {:value (join "http://" (ref :zone-record-set))
                                        :description "URL of your site"})
              dns? (assoc :hosted-zone-id {:value (ref :hosted-zone)
                                           :description "ID of HostedZone"}))})

(defn map->cf-params [m]
  (-> (fn [p [k v]]
        (conj p {:parameter-key (case/->PascalCaseString k)
                 :parameter-value v}))
      (reduce [] m)))

(defn run-template [cred stack-name template params]
  (validate-creds! cred)
  (let [tplate (json/write-str (transform-keys case/->PascalCaseString template))]
    (cformation/create-stack
     cred
     :stack-name stack-name
     :template-body tplate
     :capabilities ["CAPABILITY_IAM"]
     :parameters (map->cf-params params))))

(defn get-events [cred stack-id]
  (validate-creds! cred)
  (:stack-events (cformation/describe-stack-events cred {:stack-name stack-id})))

(defn get-outputs [cred stack-id]
  (validate-creds! cred)
  (let [sanitize #(for [o %]
                   [(case/->kebab-case-keyword (:output-key o))
                    (dissoc o :output-key)])]
    (->> (cformation/describe-stacks cred {:stack-name stack-id})
        :stacks first :outputs sanitize (into {}))))

(defn succeeded? [events]
  (->> events
       (filter #(= (:resource-status %) "CREATE_COMPLETE"))
       (filter #(= (:resource-type %) "AWS::CloudFormation::Stack"))
       seq
       boolean))

(defn failed? [events]
  (->> events
       (filter #(= (:resource-status %) "ROLLBACK_COMPLETE"))
       (filter #(= (:resource-type %) "AWS::CloudFormation::Stack"))
       seq
       boolean))

(comment
  (require '[amazonica.aws.s3 :as s3]
           '[amazonica.aws.route53 :as r53])

  (get-outputs "arn:aws:cloudformation:us-east-1:297681564547:stack/subsdufysb-martinklepsch-org-confetti-static-site/62436cc0-9290-11e5-9caa-5001b4b81a9a")

  (succeeded? (get-events "arn:aws:cloudformation:us-east-1:297681564547:stack/static-site-xyz/5aa62560-8f03-11e5-a56f-50e24162947c"))

  (run-template "static-site-xyz"
                (template {:dns? false})
                {:user-domain "tenzing.martinklepsch.org"})

  (s3/put-object :bucket-name "static-site-cljs-io-sitebucket-1969npf1zvwoh"
                 :key "index.html"
                 :file (io/file "index.html"))

  (cformation/delete-stack :stack-name "static-site-cljs-io")

  (r53/list-hosted-zones-by-name)
  ;; Get Nameservers
  (->> (r53/list-resource-record-sets :hosted-zone-id "Z19P2YAHTI3R3Z")
       :resource-record-sets
       (filter (fn [r] (= "NS" (:type r))))
       (mapcat :resource-records)
       (map :value))

  ;; (cformation/describe-stack-events :stack-name "static-site-456")

  (slurp (io/file "cf-template.json"))
  (= (old-template) (template))

  (defn old-template []
  {:description "Confetti from Clojure"
   :parameters {:user-domain {:type "String"}}
   :resources {:hosted-zone
               {:type "AWS::Route53::HostedZone"
                :properties {:hosted-zone-tags [{:key "confetti"
                                                 :value "site-xyz"}]
                             :name (ref :user-domain)}}

               :zone-record-set
               {:type "AWS::Route53::RecordSet"
                :properties {:alias-target {:d-n-s-name (attr :site-cdn :domain-name)
                                            :hosted-zone-id cloudfront-hosted-zone-id}
                             :hosted-zone-id (ref :hosted-zone)
                             :name (ref :user-domain)
                             :type "A"}}

               :site-bucket
               {:type "AWS::S3::Bucket"
                :properties {:access-control "PublicRead"
                             :website-configuration {:error-document "error.html"
                                                     :index-document "index.html"}}}

               :bucket-policy
               {:type "AWS::S3::BucketPolicy"
                :properties {:bucket (ref :site-bucket)
                             :policy-document (pol/bucket-policy (ref :site-bucket))}}

               :bucket-user
               {:type "AWS::IAM::User"
                :properties {:policies [{:policy-name (join (ref :site-bucket) "-S3-BucketFullAccess")
                                         :policy-document (pol/user-policy (ref :site-bucket))}]}}

               :bucket-user-access-key
               {:type "AWS::IAM::AccessKey"
                :properties {:status "Active"
                             :user-name (ref :bucket-user)}}

               :site-cdn
               {:type "AWS::CloudFront::Distribution"
                :properties
                {:distribution-config
                 {:comment "CDN for S3 backed website"
                  :origins [{:domain-name (attr :site-bucket :domain-name)
                             :id (attr :site-bucket :domain-name)
                             :CustomOriginConfig {:origin-protocol-policy "match-viewer"}}]
                  :default-cache-behavior {:target-origin-id (attr :site-bucket :domain-name)
                                           :forwarded-values {:query-string "false"}
                                           :viewer-protocol-policy "allow-all"}
                  :enabled "true"
                  :default-root-object "index.html"
                  :aliases [(ref :user-domain)]}}}}

   :outputs {:website-url {:value (join "http://" (ref :zone-record-set))
                           :description "URL of your site"}
             :hosted-zone-id {:value (ref :hosted-zone)
                              :description "ID of HostedZone"}
             :bucket-name {:value (ref :site-bucket)
                           :description "Name of the S3 bucket"}
             :access-key-id {:value (ref :bucket-user-access-key)
                             :description "AccessKey that can only access bucket"}
             :secret-access-key {:value (attr :bucket-user-access-key :secret-access-key)
                                 :description "Secret for AccessKey that can only access bucket"}}})

  )
