# chat-demo for clj-browserchannel

A demo app for [clj-browserchannel][1].

[1]:https://github.com/gered/clj-browserchannel

The chat-demo application is a very basic web chat application making use
of the client-side and server-side implementation for BrowserChannel provided
by clj-browserchannel. The server component is for BrowserChannel version 8.
The client component serves as a wrapper over `goog.net.BrowserChannel`
which also currently implements version 8 of the protocol.

The chat-demo web app runs in at least:

* Chrome
* Firefox
* Internet Explorer 5.5+ (!!)
* Android browser
* Others


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
Gijs Stuurman /
[@thegeez](http://twitter.com/thegeez) /
[Blog](http://thegeez.github.com) /
[GitHub](https://github.com/thegeez)

Many updates in this fork by:
Gered King /
[@geredking](http://twitter.com/geredking) /
[GitHub](https://github.com/gered)

### License

Copyright (c) 2012 Gijs Stuurman and released under an MIT license.
