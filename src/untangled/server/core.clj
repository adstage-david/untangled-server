(ns untangled.server.core
  (:require [untangled.server.impl.components.web-server :as web-server]
            [untangled.server.impl.components.handler :as handler]
            [untangled.server.impl.components.config :as config]
            [untangled.server.impl.components.access-token-handler :as access-token-handler]
            [untangled.server.impl.components.openid-mock-server :as openid-mock-server]
            [untangled.server.impl.pretty-system :as pretty-system]
            [com.stuartsierra.component :as component]
            [clojure.data.json :as json]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mutation Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn arg-assertion [mutation & args]
  "The function will throw an assertion error if any args are nil."
  (assert (every? (comp not nil?) args) (str "All parameters to " mutation " mutation must be provided.")))

(defn assert-user [req]
  "Throws and AssertionError if the user credentials are missing from the request."
  (assert (:user req) "Request has no user credentials!"))

(defn transitive-join
  "Takes a map from a->b and a map from b->c and returns a map a->c."
  [a->b b->c]
  (reduce (fn [result k] (assoc result k (->> k (get a->b) (get b->c)))) {} (keys a->b)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; OpenID helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn openid-location [{:keys [config] :as env} match]
  "A helper endpoint that can be injected via untangled server's :extra-routes.
  This allows untangled clients to access the configuration they require to begin the OpenID auth process."
  (let [openid-config (-> config :value :openid)
        url (str (:authority openid-config) "/connect/authorize")]
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (json/write-str {:authUrl  url
                               :scope    (:scope openid-config)
                               :clientId (:client-id openid-config)})}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component Constructor Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-web-server []
  (component/using
    (web-server/map->WebServer {})
    [:handler :config]))

(defn raw-config
  "Creates a configuration component using the value passed in,
   it will NOT look for any config files."
  [value] (config/map->Config {:value value}))

(defn new-config
  "Create a new configuration component. It will load the application defaults from config/defaults.edn
   (using the classpath), then look for an override file in either:
   1) the file specified via the `config` system property
   2) the file at `config-path`
   and merge anything it finds there over top of the defaults.

   This function can override a number of the above defaults with the parameters:
   - `config-path`: The location of the disk-based configuration file.
   "
  [config-path]
  (config/map->Config {:config-path config-path}))

(defn build-access-token-handler [& {:keys [dependencies]}]
  (component/using
    (access-token-handler/map->AccessTokenHandler {})
    (into [] (cond-> [:config :handler :server :openid-mock]
               dependencies (concat dependencies)))))

(defn build-mock-openid-server []
  (component/using
    (openid-mock-server/map->MockOpenIdServer {})
    [:config :handler]))

(defn build-test-mock-openid-server []
  (component/using
    (openid-mock-server/map->TestMockOpenIdServer {})
    [:config]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server Construction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn make-untangled-server
  "Make a new untangled server.

  Parameters:
  *`config-path`        OPTIONAL, a string of the path to your configuration file on disk.
                        The system property -Dconfig=/path/to/conf can also be passed in from the jvm.

  *`components`         OPTIONAL, a map of Sierra component instances keyed by their desired names in the overall system component.
                        These additional components will merged with the untangled-server components to compose a new system component.

  *`parser`             REQUIRED, an om parser function for parsing requests made of the server. To report errors, the
                        parser must throw an ExceptionInfo with a map with keys `:status`, `:headers`, and `:body`.
                        This map will be converted into the response sent to the client.

  *`parser-injections`  a vector of keywords which represent components which will be injected as the om parsing env.

  *`extra-routes`       OPTIONAL, a map containing `:routes` and `:handlers`,
                        where routes is a bidi routing data structure,
                        and handlers are map from handler name to a function of type :: Env -> BidiMatch -> Res
                        see `handler/wrap-extra-routes` & handler-spec for more.

  *`app-name`           OPTIONAL, a string that will turn \"\\api\" into \"<app-name>\\api\"

  *`pretty/big-data`    OPTIONAL, a set of keywords in the system to only log when start fails if the timbre level is trace

  Returns a Sierra system component.
  "
  [& {:keys [app-name config-path parser parser-injections
             components extra-routes
             pretty/big-data]
      :or {config-path "/usr/local/etc/untangled.edn"}}]
  {:pre [(some-> parser fn?)
         (or (nil? components) (map? components))
         (or (nil? parser-injections) (every? keyword? parser-injections))]}
  (let [handler (handler/build-handler parser parser-injections
                                       :extra-routes extra-routes
                                       :app-name app-name)
        built-in-components [:config (new-config config-path)
                             :handler handler
                             :server (make-web-server)]
        all-components (flatten (concat built-in-components components))]
    (prn :big-data big-data)
    (pretty-system/wrap-system
      (apply component/system-map all-components)
      :pretty/big-data big-data)))

(defn make-untangled-test-server
  "Make sure to inject a :seeder component in the group of components that you pass in!"
  [& {:keys [parser parser-injections
             components
             pretty/big-data]}]
  (let [handler (handler/build-handler parser parser-injections)
        built-in-components [:config (new-config "test.edn")
                             :handler handler]
        all-components (flatten (concat built-in-components components))]
    (pretty-system/wrap-system
      (apply component/system-map all-components)
      :pretty/big-data big-data)))
