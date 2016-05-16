# clj-browserchannel

Cross-browser compatible, real-time, bi-directional
communication between ClojureScript and Clojure using Google Closure
BrowserChannel.

## goog.net.BrowserChannel

From the Google Closure API: "A [BrowserChannel][1] simulates a
bidirectional socket over HTTP. It is the basis of the Gmail Chat IM
connections to the server." 
The javascript api of BrowserChannel is open-source and part of the
Google Closure library. The server component is not, as is noted in
the Google Closure book ("Closure: The Definitive Guide by Michael Bolin").

[1]: https://google.github.io/closure-library/api/source/closure/goog/net/browserchannel.js.src.html

## Usage

[clj-browserchannel][2] is the main library containing both the server- and
client-side functionality you'll use in your web apps.

In order to use the server implementation of BrowserChannel you'll need to
use an async adapter. Currently the provided options are:

* [clj-browserchannel-jetty-adapter][3]
* [clj-browserchannel-immutant-adapter][4]

[2]: https://github.com/gered/clj-browserchannel/tree/master/clj-browserchannel
[3]: https://github.com/gered/clj-browserchannel/tree/master/clj-browserchannel-jetty-adapter
[4]: https://github.com/gered/clj-browserchannel/tree/master/clj-browserchannel-immutant-adapter

You can find more information on usage of all of these components by
following any of the above links to them.

## Demo

The [chat-demo][2] application is an example chat application using a
client-side and server-side implementation for BrowserChannel written in
Clojure/ClojureScript. The server component is for BrowserChannel version 8.
The client component serves as a wrapper over `goog.net.BrowserChannel`.

[2]: https://github.com/gered/clj-browserchannel/tree/master/chat-demo

The chat-demo web app runs in at least:

* Chrome
* Firefox
* Internet Explorer 5.5+ (!!)
* Android browser

## Related and alternative frameworks

* Websockets - Websockets solve the same problems as BrowserChannel,
  however BrowserChannel works on almost all existing clients.
* socket.io - [socket.io][3] provides a similar api as BrowserChannel on
  top of many transport protocols, including websockets. BrowserChannel
  only has two transport protocols: XHR and forever frames (for IE) in
  streaming and non-streaming mode.

[3]: http://socket.io

## Other BrowserChannel implementations
Many thanks to these authors, their work is the only open-source
documentation on the BrowserChannel protocol.

* [libevent-browserchannel-server][libevent]
in C++ by Andy Hochhaus - Has the most extensive [documentation][libevent-doc] on the BrowserChannel protocol
* [browserchannel][ruby] in Ruby by David Turnbull
* [node-browserchannel][node]
in Node.js/Javascript by Joseph Gentle

[libevent]: http://code.google.com/p/libevent-browserchannel-server
[libevent-doc]: http://web.archive.org/web/20121226064550/http://code.google.com/p/libevent-browserchannel-server/wiki/BrowserChannelProtocol
[ruby]: https://github.com/dturnbull/browserchannel
[node]: https://github.com/josephg/node-browserchannel

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
