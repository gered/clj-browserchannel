# chat-demo for clj-browserchannel

Cross-browser compatible, real-time, bi-directional
communication between ClojureScript and Clojure using Google Closure
BrowserChannel.

See also: [clj-browserchannel][1]
[1]:https://github.com/thegeez/clj-browserchannel

## Demo

clj-browserchannel-demo is an example chat application using a server
side implementation for BrowserChannel written in Clojure. The server
component is for BrowserChannel version 8.

This enables client->server and server->client communication in
ClojureScript and Closure web apps, without any javascript
dependencies other than the Google Closure [library][2].

[2]: https://developers.google.com/closure/library/

The example runs in at least:

* Chrome
* Firefox
* Internet Explorer 5.5+ (!!)
* Android browser

## Running

You can either start it up directly from a REPL by simply running:

    (-main)

Or you can run it easily from a command line via the included Leiningen
alias to build and run everything:

    $ lein rundemo

Once the application server is running, you can then open up
http://localhost:8080/ in your browser. Open it in multiple browser
windows to see the chat communication in action.

At the bottom of the `-main` function in `chat-demo.server`, you can 
comment/uncomment out the different `run-` functions to choose which 
web server you want to test out the demo with.

## About

Written by:
Gijs Stuurman / [@thegeez][twt] / [Blog][blog] / [GitHub][github]

[twt]: http://twitter.com/thegeez
[blog]: http://thegeez.github.com
[github]: https://github.com/thegeez

Updated by:
Gered King / [@geredking][twt] / [Github][github]

[twt]: http://twitter.com/geredking
[github]: https://github.com/gered

### License

Copyright (c) 2012 Gijs Stuurman and released under an MIT license.
