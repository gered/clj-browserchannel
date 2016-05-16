# clj-browserchannel-jetty-adapter

Jetty async adapter for BrowserChannel. 

See also: [clj-browserchannel][1]

[1]:https://github.com/gered/clj-browserchannel


## Leiningen

    [gered/clj-browserchannel-jetty-adapter "0.1.0"]


## Usage

This library does not directly include Jetty as a dependency so that
it's not tied to one specific version of Jetty (allowing you to upgrade
to newer versions easier as long as they remain compatible with this
library). You will need to include these Ring dependencies at a minimum:

* `ring/ring-core`
* `ring/ring-servlet`
* `ring/ring-jetty-adapter`

The top-level `ring` dependency includes all of these. See the
[Ring][2] project page for more information and the relevant
dependency lines for your `project.clj`.

[2]: https://github.com/ring-clojure/ring

To enable server-side BrowserChannel functionality, you should
start Jetty with the included `run-jetty` function. For example:

```clj
(ns your-app
  (:require
    ; ...
    [net.thegeez.browserchannel.server :refer [wrap-browserchannel]
    [net.thegeez.browserchannel.jetty-async-adapter :refer [run-jetty]]
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
      (wrap-browserchannel event-handlers)))

(defn -main [& args]
  (run-jetty
    #'handler
    {:join? false
     :port  8080}))
```

The `run-jetty` function takes the exact same set of options as
`ring.adapter.jetty/run-jetty` does. See that function for more
information.

One additional option is made available to configure the length of
time that BrowserChannel async requests will remain open before
timing out. By default this timeout is 4 minutes. This default 
time period is based on what Google uses currently in their 
BrowserChannel usage on e.g. Gmail. 

To change this, the option you need to pass in to `run-jetty` is
`:response-timeout` with a new time in milliseconds:

```clj
(run-jetty
  #'handler
  {:join? false
   :port 8080
   ; 2 minute async request timeout
   :response-timeout (* 2 60 1000)})
```

This timeout period directly controls the maximum time that a
back channel request can remain open for before it gets closed
and the client must open a new one.


## About

Written by:
Gijs Stuurman /
[@thegeez](http://twitter.com/thegeez) /
[Blog](http://thegeez.github.com) /
[GitHub](https://github.com/thegeez)

Updates in this fork by:
Gered King /
[@geredking](http://twitter.com/geredking) /
[GitHub](https://github.com/gered)

### License

Copyright (c) 2012 Gijs Stuurman and released under an MIT license.
