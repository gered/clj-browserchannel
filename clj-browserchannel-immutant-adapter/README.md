# clj-browserchannel-immutant-adapter

Immutant async adapter for BrowserChannel.

See also: [clj-browserchannel][1]

[1]:https://github.com/gered/clj-browserchannel


## Leiningen

    [gered/clj-browserchannel-immutant-adapter "0.0.2"]


## Usage

This library does not directly include Immutant as a dependency so
that it's not tied to a specific version of Immutant. You will need 
to also include Immutant as a dependency directly in your project.

[See the Immutant project for the relevant dependency line.][2]

[2]: https://github.com/immutant/immutant

To enable server-side BrowserChannel functionality, you simply
need to add the `wrap-immutant-async-adapter` middleware to your
Ring handler. For example:

```clj
(ns your-app
  (:require
    ; ...
    [net.thegeez.browserchannel.server :refer [wrap-browserchannel]
    [net.thegeez.browserchannel.immutant-async-adapter :refer [wrap-immutant-async-adapter]]
    [immutant.web :as immutant]
    ; ...
    ))

(def event-handlers
  ; ... browserchannel event handler map ...
  )

(def your-app-routes
  ; ...
  )

(def ring-handler
  (-> your-app-routes
      ; other middleware
      (wrap-browserchannel event-handlers)
      (wrap-immutant-async-adapter)))

(defn -main [& args]
  (immutant/run
    #'ring-handler
    {:port 8080}))
```

By default, BrowserChannel async requests will timeout after 4 minutes.
This default time period is based on what Google uses currently in
their BrowserChannel usage on e.g. Gmail. If you would like to change this,
simple pass in `:response-timeout` with a new time in milliseconds to
`wrap-immutant-async-adapter`:

```clj
(def ring-handler
  (-> your-app-routes
      ; other middleware
      (wrap-browserchannel event-handlers)
      ; 2 minute async request timeout
      (wrap-immutant-async-adapter {:response-timeout (* 2 60 1000})))
```

This timeout period directly controls the maximum time that a
back channel request can remain open for before it gets closed
and the client must open a new one.

## About

Written by:
Gered King /
[@geredking](http://twitter.com/geredking) /
[GitHub](https://github.com/gered)

### License

Copyright (c) 2016 Gered King and released under an MIT license.