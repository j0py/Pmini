/*******************************************************************

20220423

create trees from a spec (a string)

each tree can then be traversed to generate keys for an event
each tree is capable of generating \dur, but only one will

N number       \type \dur \sustain \degree \bufnum
A amplitude    \type \dur \amp
V amplitude    \type \dur \amp
O octave       \type \dur \octave
P pan          \type \dur \pan

example: Pmini(\sound, "N1234A5O4P08")
returns:

Pbind(
  \instrument, \sound,   // \sound does not exists as a sample
  \instrument, \playbuf, // if \sound exists as a sample
  \bufnum, (calculated), // if \sound exists as a sample
  \dur, 1,               // if beatsPerBar = 4
  \degree, Pseq([1,2,3,4], inf),
  \amp, 5,
  \octave, 4,
  \pan, Pseq([-1, 1], inf),
  );

each tree is created from its own spec string. spec syntax:

0 .. z  integer value 0 .. 35
%       random number 1 .. 9 generated once at interpretation time
?       random number 1 .. 9 generated new for each event
-       plays a rest
~       glides previous step to a new value
<space> adds sustain to previous step

*n plays step n times faster within the step duration
/n plays step n times slower within the step duration
!n repeats a step n times
'n adds n * 0.1 to degree, or speeds up sample playback rate by n
,n substract n * 0.1 to degree, or slows down sample playback rate by n

<..>    plays enclosed steps one by one
[..]    plays enclosed steps within this step (faster)
{..}    plays one of the enclosed steps randomly
(..)n   plays enclosed steps together, strum: 0..b neg, c none, d..o pos

strum not yet implemented
idea: use "|" to end the stream by returning nil in the next event
idea: use "!0" (repeat 0 times) to disable a step, !1 to enable again
idea: "K" = drumkit
  Pmini.kit = [$_, "bd", 1, $=, "sn", 2, $^, "hh", 3,.. etc]
  characters useable in "K" spec: #^*-_=':;,.\
  Pmini("K_'<^[^^]>(_')V8") if "K" is used, then sound arg is ignored

******************************************************************/

Pmini {
	classvar playbuf_synthdef=\playbuf;

	*new { |sound="", spec=""| ^super.new.init(sound, spec); }

	init { |sound, spec|
		// the optional arguments start from the left side here
		if(spec.asString.size <= 0, { spec = sound; sound="default"; });

		// if spec contains uppercase letters, then return a Pbind
		if(spec.toLower != spec, { ^this.return_pbind(sound.asSymbol, spec) });

		^this.return_pattern(sound, spec);
	}

	return_pattern { |sound, spec|
		var root = PMRoot(spec);

		case

		// [\dur, \degree]
		{ sound == "dd" } { ^Pn(Plazy({
			var event = root.next;
			Pseq([event.dur, event.intval], 1)
		}));
		}

		// \dur
		{ sound == "d" } { ^Pn(Plazy({ root.next.dur })) }

		// \degree
		{	^Pn(Plazy({ root.next.intval })) }
	}

	return_pbind { |sound, spec|
		var part, parts, dur_part, pb;

		// split spec in parts and determine which one defines the durations.
		// defaults to first part, overridden by last part with a "+" in its spec
		//
		parts = Dictionary.new;
		spec.asString.do { |ch|
			case
			{ ch.isAlpha.and(ch.isUpper) } { part = ch.toLower.asSymbol }
			{ part.isNil } { } // wait for the first uppercase char
			{ ch == $+ } { dur_part = part }
			{ parts.put(part, parts.atFail(part, "") ++ ch.asString); };

			if(dur_part.isNil.and(part.notNil), { dur_part = part; });
		};

		// parse each part, resulting in a PMRoot object (a tree)
		parts = parts.collect { |spec| PMRoot(spec) };

		pb = Pbind(
			\dur, Pfunc({ |ev|
				var dur, sustain, mid, samples, rest=false, up=0, down=0, degree=0;

				// let each PMRoot create its next PMEvent object
				var events = parts.collect { |root| root.next };

				dur = events.at(dur_part).dur.asFloat;
				sustain = events.at(dur_part).sustain.asFloat;

				if(events.includesKey(\n), {
					degree = events.at(\n).intval;
					up = events.at(\n).intup;
					down = events.at(\n).intdown;
				});

				events.do { |event| if(event.type == \rest, { rest = true }) };

				if(rest, {
					ev.put(\degree, \rest);
				}, {
					var amp;

					if((samples = Library.at(\samples, sound)).notNil, {
						ev.put(\instrument, playbuf_synthdef);
						ev.put(\bufnum, samples.wrapAt(degree).bufnum);
						ev.put(\rate, (up - down).midiratio);
					}, {
						ev.put(\instrument, sound);
						ev.put(\degree, (up - down) * 0.1 + degree);

						// glide mechanism
						if(events.includesKey(\n), {
							if(events.at(\n).type == \note, {
								// setup callback which stores the id of the synth in 'mid'
								ev.put(\callback, { |ev2|
									if(ev2.id.class == Array.class, {
										mid = ev2.id.at(0);
									}, {
										mid = ev2.id.asInteger;
									});
								});
								ev.put(\sustain, sustain);
							});

							if(events.at(\n).type == \set, {
								ev.put(\type, \set);
								ev.put(\id, mid);
								ev.put(\sustain, (dur * 0.5));
							});
						});

						// \octave
						if(events.includesKey(\o), {
							ev.put(\octave, events.at(\o).intval.clip(2,8));
						});
					});

					// \amp
					if(events.includesKey(\a), {
						if(events.includesKey(\v), {
							ev.put(
								\amp,
								max(0, (events.at(\v).intval.clip(0,8) - 9 * 6).dbamp - 0.002) *
								max(0, (events.at(\a).intval.clip(0,8) - 9 * 6).dbamp - 0.002)
							);
						}, {
							ev.put(
								\amp,
								max(0, (events.at(\a).intval.clip(0,8) - 9 * 6).dbamp - 0.002)
							);
						});
					}, {
						if(events.includesKey(\v), {
							ev.put(
								\amp,
								max(0, (events.at(\v).intval.clip(0,8) - 9 * 6).dbamp - 0.002)
							);
						});
					});

					// \pan
					if(events.includesKey(\p), {
						ev.put(\pan, events.at(\p).intval.clip(0,8) / 4 - 1);
					});
				});

				dur
			})
		);

		^pb;
	}
}

PMRoot : PMNested {
	var index = 0, str, cycle, events;

	*new { |spec| ^super.new.init(spec.asString) }

	init { |spec|
		str = spec;
		cycle = -1;
		events = List.new;
		^this.parse(this);
	}

	parse { |currentNode|
		var node, parsing=\value;

		while
		{ index < str.size } {
			var ch = str.at(index);
			index = index + 1;

			if(ch == $%, { ch = "123456789".at(9.rand) });

			case
			{ ch == $- } {
				node = PMRest.new;
				currentNode.addChild(node);
				parsing = \value;
			}
			{ ch == $  } {
				node = PMSpace.new;
				currentNode.addChild(node);
				parsing = \value;
			}
			{ ch == $< } {
				node = PMTurns.new;
				this.parse(node);
				currentNode.addChild(node);
				parsing = \value;
			}
			{ ch == $> } { ^this }
			{ ch == ${ } {
				node = PMRandom.new;
				this.parse(node);
				currentNode.addChild(node);
				parsing = \value;
			}
			{ ch == $} } { ^this }
			{ ch == $[ } {
				node = PMNested.new;
				this.parse(node);
				currentNode.addChild(node);
				parsing = \value;
			}
			{ ch == $] } { ^this }
			{ ch == $~ } { parsing = \glide }
			{ ch == $* } { parsing = \faster }
			{ ch == $/ } { parsing = \slower }
			{ ch == $! } { parsing = \repeat }
			{ ch == $' } { parsing = \up }
			{ ch == $, } { parsing = \down }
			{ ch == $( } {
				node = PMChord.new;
				currentNode.addChild(node);
				parsing = \chord;
			}
			{ ch == $) } { parsing = \value }

			{ parsing == \chord } {
				currentNode.children.last.add_value(ch);
			}
			{ parsing == \value } {
				// start new node and parse the value (a 1 digit number, or '?')
				node = PMNote.new;
				node.value_(ch);
				currentNode.addChild(node);
			}
			{ parsing == \glide } {
				// start new node and parse the value (a 1 digit number, or '?')
				node = PMGlide.new;
				node.value_(ch);
				currentNode.addChild(node);
				parsing = \value;
			}
			{ parsing == \faster } {
				if(node.notNil, { node.faster_(ch) });
				parsing = \value;
			}
			{ parsing == \slower } {
				if(node.notNil, { node.slower_(ch) });
				parsing = \value;
			}
			{ parsing == \up } {
				if(node.notNil, { node.up_(ch) });
				parsing = \value;
			}
			{ parsing == \down } {
				if(node.notNil, { node.down_(ch) });
				parsing = \value;
			}
			{ parsing == \repeat } {
				if(node.notNil, {
					(max(1, ch.digit) - 1).do {
						node = currentNode.addChild(node.clone);
					};
				});
				parsing = \value;
			}
			{ }; // default no action
		};

		^this; // you are the root node of the tree, so return yourself
	}

	next {
		var event, index, type, sustain;

		if(events.size < 1, { this.make_more_events(); });

		event = events.removeAt(0);

		if(event.type == \space, { event.type_(\rest); ^event });
		if(event.type == \glide, { event.type_(\set); ^event });
		if(event.type == \rest, { ^event });

		// we have a \note here

		// make_more_events until you encounter the next \note or \rest
		// and calculate \sustain for the current \note by summing the
		// \dur values of all the \space and \glide events up until the
		// next \note.
		// after calculating this \sustain, then you can return the
		// current \note or \rest event to be played.

		sustain = event.dur * 0.8;

		if(events.size < 1, { this.make_more_events() });

		index = 0;
		type = events.at(index).type;

		while { (type != \note).and(type != \rest) }
		{
			//["--", index, type, events.at(index).dur, sustain].postln;
			sustain = sustain + events.at(index).dur;
			index = index + 1;
			if(events.size <= index, { this.make_more_events() });
			type = events.at(index).type;
		};

		event.sustain_(sustain);

		^event;
	}

	make_more_events {
		var dur = 4;

		if(thisThread.clock.class == TempoClock.class, {
			dur = thisThread.clock.beatsPerBar;
		});

		cycle = cycle + 1;

		events.addAll(super.get_events(cycle, dur));
	}
}

PMNote : PMNode {
	traverse { |cycle, dur|
		^List.newFrom([
			PMEvent.newFromPMNode(this, \note, this.alter(dur), value)
		]);
	}
}

PMChord : PMNode {
	traverse { |cycle, dur|
		^List.newFrom([
			PMEvent.newFromPMNode(this, \note, this.alter(dur), value)
		]);
	}

	add_value { |ch|
		if(value.isArray, {
			value = value.add(ch);
		}, {
			value = [ch];
		});
	}
}

PMRest : PMNode {
	traverse { |cycle, dur|
		^List.newFrom([
			PMEvent.newFromPMNode(this, \rest, this.alter(dur))
		]);
	}
}

PMSpace : PMNode {
	traverse { |cycle, dur|
		^List.newFrom([
			PMEvent.newFromPMNode(this, \space, this.alter(dur))
		]);
	}
}

PMGlide : PMNode {
	traverse { |cycle, dur|
		^List.newFrom([
			PMEvent.newFromPMNode(this, \glide, this.alter(dur), value)
		]);
	}
}

PMNested : PMNode {
	traverse { |cycle, dur|
		var d, result = List.new;

		d = this.alter(dur) / children.size;
		children.do { |node| result.addAll(node.get_events(cycle, d)) }
		^result;
	}
}

PMTurns : PMNode {
	traverse { |cycle, dur|
		var node = children.wrapAt(cycle);
		^List.newFrom(node.get_events(cycle, this.alter(dur)));
	}
}

PMRandom : PMNode {
	traverse { |cycle, dur|
		var node = children.choose;
		^List.newFrom(node.get_events(cycle, this.alter(dur)));
	}
}

PMNode {
	var <>parent, <children, <>prev, <>next, <>value;
	var <>faster=$1, <>slower=$1;
	var <>up=$0, <>down=$0;
	var remaining_events, remain = 0;

	*new { ^super.new.initPMNode; }

	initPMNode {
		children = List.new;
		remaining_events = List.new;
		^this
	}

	clone {
		var clone = this.class.new;
		clone.value_(value);
		clone.faster_(faster);
		clone.slower_(slower);
		clone.up_(up);
		clone.down_(down);

		children.do { |child| clone.addChild(child.clone) };

		^clone;
	}

	addChild { |node|
		node.parent_(this);
		if(children.size > 0, {
			children.last.next_(node);
			node.prev_(children.last);
		});

		children.add(node);

		^node;
	}

	// @return List[PMEvent, PMEvent, ..]
	get_events { |cycle, dur|
		var result = List.new;
		var stop = 0, duration = dur;

		while
		{ (duration > 0).and(stop <= 0) }
		{
			if(remain > 0, {
				if(duration >= remain, {
					result.add(PMEvent(\space, remain, $0));
					duration = duration - remain;
					if(duration < 0.0001, { duration = 0; });
					remain = 0;
				}, {
					result.add(PMEvent(\space, duration, $0));
					remain = remain - duration;
					if(remain < 0.0001, { remain = 0; });
					duration = 0;
				});
			}, {
				if(remaining_events.size > 0, {
					var event = remaining_events.removeAt(0);
					if(event.dur > duration, {
						result.add(PMEvent.newFromPMEvent(event, duration));
						remain = event.dur - duration;
						if(remain < 0.0001, { remain = 0; });
						duration = 0;
					}, {
						result.add(event);
						duration = duration - event.dur;
						if(duration < 0.0001, { duration = 0; });
					});
				}, {
					remaining_events.addAll(this.traverse(cycle, dur));
					if(remaining_events.size <= 0, { stop = 1; });
					// prevent endless loop
				});
			});
		};

		^result;
	}

	post { |indent=""|
		(indent ++ this.log).postln;
		children.do({ |node| node.post(indent ++ "--") });
		^this;
	}

	log {
		^format(
			"% %/% % % \"%\"",
			this.class.name, slower, faster, up, down, value.asString
		)
	}

	alter { |dur|
		case
		{ slower == $? }
		{ dur = dur * (2 + 7.rand) }
		{ dur = dur * max(1, slower.digit) };

		case
		{ faster == $? }
		{ dur = dur / (2 + 7.rand) }
		{ dur = dur / max(1, faster.digit) };

		^dur;
	}
}

// random value ? is deferred until inside the constructor of this class
PMEvent {
	var <>type, <>dur, <val=$0, <>up=$0, <>down=$0, <>sustain=0;

	*new { | type, dur, val=$0, up=$0, down=$0 |
		^super.newCopyArgs(type, dur.asFloat, val, up, down);
	}

	*newFromPMNode { | node, type, dur, val=$0 |
		^PMEvent.new(type, dur, val, node.up, node.down);
	}

	*newFromPMEvent { | ev, dur |
		^super.newCopyArgs(ev.type, dur.asFloat, ev.val, ev.up, ev.down);
	}

	symval {
		case
		{ val.isArray }
		{ ^val.collect { |it|
			case
			{ it == $? } { 9.rand.asSymbol }
			{ it.asSymbol };
		}}
		{ case { val == $? } { ^9.rand.asSymbol } { ^val.asSymbol }};
	}

	intval {
		case
		{ val.isArray }
		{ ^val.collect { |it|
			case
			{ it == $? } { 9.rand }
			{ it.digit };
		}}
		{ case { val == $? } { ^9.rand } { ^val.digit }};
	}

	intup { case { up == $? } { ^(1 + 8.rand) } { ^up.digit } }

	intdown { case { down == $? } { ^(1 + 8.rand) } { ^down.digit } }

	post {
		format(
			"% % % % % % %",
			this.class.name,
			type,
			dur,
			sustain,
			val,
			up,
			down
		).postln;

		^this;
	}
}
