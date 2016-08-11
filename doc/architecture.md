This is really just notes from 2016-AUG-05. It's subject to
change without any notice.

# System

Basic Stuart Sierra Component SystemMap.

This is really meant to be used as a library by the Renderer.

Though I originally envisioned it as its a stand-alone proxy process.

## Current Components

### Context

This is really an instance of common.zmq-socket/ContextWrapper.

Just a minimalist wrapper over a 0mq Context to let me declare it
as a Component and then let Lifecycle cope with start/stop.

### ConnectionManager

Depends on the Context.

# client.connection-manager.ConnectionManager

This is supposed to establish a connection to the Server and then hand
it over to the CommunicationsLoopManager.

Which is probably the most important of its members.

It's definitely the most well-thought out. Its murkier siblings are
sketched out below it.

## common.manager.CommunicationsLoopManager

This is really just a container for a map of WorldId=>EventPair.

### WorldId

Pretty nebulous. Really should be a UUID, but I'm not sure what
should generate it.

And, honestly, it should probably be a tuple that includes the Server's
App ID, the Server ID, and the ID that the Renderer has assigned.

Major conceptual problem with existing approach: I'm trying to
minimize open Server ports. Which means that worlds really have
to share a socket.

Well, they don't have to. This is something to keep in mind. Even if
they all go over the same port, each could use its own Socket.

That seems like it would be very inefficient if they're
all going over the same port, but it would make the actual connection
management code simpler/easier. So it's worth experimenting to see what
sort of impact it actually has.

### common.async-zmq.EventPair

This Component really has two pieces that matter:

#### ex-chan

core.async channel. Read it to receive data that's been sent to the Socket.

I keep going back and forth about this sort of thing really needing to
be wrapped in its own Component so I can just declare it as part of
a System and let the Lifecycle handle creating/closing it for me.

That isn't a concern here: the EventPair owns this, so it's perfectly
reasonable for it to handle that part.

Although...making that declarative would simplify this code a bit.

#### common.async-zmq.EventPairInterface

This also, really, has more interesting pieces.

##### in-chan

core.async channel. Write to it to send data out.

##### ex-sock

This is a 0mq socket that communicates with the outside world.

For the Client, this is really a Dealer that communicates with
a single Server.

##### Status Reporting

##### status-chan

Write your own core.async Channel to this core.async Channel
to request a status report. That will be published back out over
the Channel you supplied.

#### Explanation

For the Client, each connection to a Server is really an EventPair.

I should probably just merge it with the EventPairInterface where
the actual work happens, but these pieces already feel too
big and convoluted to me.

TODO: Ask for opinions on #clojure

## auth-loop

The comments say "Really needs to be a map
Maybe of URLs to the go loops?"

It's pretty obviously a go loop.

### Q: What is this actually doing?

A: It listens to the auth-request and status-check channels.

When it receives an auth-request message, it calls dispatch-auth-response!

#### dispatch-auth-response!

This checks for an unexpired AUTH dialog description. If it doesn't find one,
it requests another from the server and sends a :hold-on response.

### More important Q: Does it make any sense here?

A: Nope. Not as written, anyway.

## auth-request

Another core.async channel

Q: What is this for?

It's used in the initiate-handshake function. We write a request (with a response
channel) to it periodically until we either get a response or give up.



## local-auth-url

A 0mq URL. Presumably for connecting to "local" Server.

## message-context

This is the 0mq ContextWrapper. This one, at least, seems safe enough.

## status-check

A core.async channel. My first guess is this is for sending status
requests to the EventLoops.

## worlds

Current comments:

     ;; A map of world-ids to auth-connections
     ;; It might make more sense to have multiple ConnectionManager
     ;; instances, each with a single world.
     ;; Alternatively, stick this inside the atom so
     ;; we don't have to worry about state: worlds
     ;; is just a map of values.
     ;; That approach is much more tempting, but
     ;; this entire thing is inherently stateful.
     ;; So run with this approach for now

I'm not sure [yet] what that actually means/meant.

### auth-connections

Map of world-id to individiual-auth-connection

### individual-auth-connection

Map of cljeromq.common/Socket to optional-auth-dialog-description.

This includes a comment that the Socket keys include the URL.

TODO: Verify that. I think they're just supposed to be black boxes.

### optional-auth-dialog-description

A placeholder for dialog descriptions that we might not have received
yet.

The basic implementation I have so far involves a loop to pull these
from the Server if we don't have one yet.

### auth-dialog-description

This is either an auth-dialog-url-description or an auth-dialog-dynamic-description.

Both are based on base-auth-dialog-description

#### base-auth-dialog-description

This is for downloading HTML/javascript that can be used for logging in.

It's pretty much exactly what I had in mind for the Login App.

Hopefully it just needs to be made more general so it can serve up any
App.

#### auth-dialog-url-description

This adds a static URL for downloading bigger, more complicated pieces that
we don't want loading down the App server.

#### auth-dialog-dynamic-description

For Worlds that are small enough to just send the UI description directly.

# Interaction

## ConnectionManager Extras

Its schema includes pieces for defining a UI. Seems to
be tightly focused on creating an authentication dialog.

TODO: Need to see how this meshes with more recent schema
for Apps in general.

## Session Creation/Management

This really doesn't belong here.

## RPC Pieces

TODO: What are these for, really?

## ConnectionManager Lifecycle

### Start

#### establish-connection

This connects to a Server and requests a new authentication UI.

##### freshen-auth!

First it checks whether we have a fresh UI description sitting on the socket
waiting to be used.

If there is, it throws a TODO exception because we really need to pass that
description along to the CommunicationsLoopManager. I'm pretty sure this is
where I stopped the last time I found an opportunity to mess with this.

If there isn't, this calls request-auth-descr! and returns nil so this
will get called again, later.

##### request-auth-descr!

This really just sends a fairly generic message to the Server to start the
conversation.

#### auth-loop-creator

This connects another socket to the Server and sends request-auth-descr! again.

And then it sits around in a loop listening for requests on core.async channels.

The main channel it cares about is one for auth requests. Callers send that a
callback async.channel. This puts the return value from dispatch-auth-response! onto
that callback channel.

#### dispatch-auth-response!

Starts by calling (unexpired-auth-description).

If there's an available and unexpired auth dialog description, we pre-process
it into a shape the Renderer expects and sends that to the callback channel.

Otherwise it responds with a wait.

#### unexpired-auth-description


### Stop

This calls release-world! for all the vals in the worlds map atom.

#### release-world!
