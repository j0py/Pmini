/*******************************************************************

create a tree from a spec (a string), which can then be traversed

node types for the tree (and the list too):
- nested : contains one or more children, who share time
- turns  : contains one or more children, taking turns, one per cycle
- note   : plays a note (\degree, \dur, \type)
- rest   : plays a rest (\dur, \type)
- space  : adds sustain (time) to previous node, by increasing its \dur
- root   : is essentially a nested node, traversal start point

spec: " 1x<6[78]>4"

tree: root - space
- note (1)
- rest
- turns  - note (6)
- nested - note (7)
- note (8)
- note (4)

traverse the tree, per cycle (total dur = 1) number (number/dur):
0 : space(/.2), note(1/.2), rest(/.2), note(6/.2), note(4/.2)
1 : space(/.2), note(1/.2), rest(/.2), note(7/.1), note(8/.1), note(4/.2)
2 : space(/.2), note(1/.2), rest(/.2), note(6/.2), note(4/.2)
3 : space(/.2), note(1/.2), rest(/.2), note(7/.1), note(8/.1), note(4/.2)
etc

each cycle, you can traverse the tree, and add the nodes to the end of a list.

algoritm when you have to generate the next event:

0. while list is empty, cycle++ and traverse tree to add nodes to the list
1. take node N from the start of the list
2. if N == space then play a \rest -> done
3. do forever
if list is empty, cycle++ and traverse tree to add nodes to the list
if space node at start of list: take it out (M) and add it's \dur to N
else break from do forever
4. N == rest ? play a \rest -> done
5. N == note ? play a \note -> done
6. error

this algorithm can be implemented inside a Pn/Plazy construct

more ideas:
-----------
TEST samples: ', + 1 digit (0..9) rate = rate * / digit.midiratio
TEST synthdef: ',  sharp/flat (degree +/- 0.1) --> degree should be float!

TEST * and / always expect 1 digit (*2/3 == /1.5)
TEST (then putting a "|" after to end the step is not needed anymore)

TEST "O" octave spec, default octave = 5

TEST implement !, + 1 digit (2..9) to "repeat" a step

TEST randomness
TEST % = random digit 0..9, use everywhere, evaluated once during parse
TEST ? = random digit 0..9, use everywhere, evaluated just in time all the time

TEST alternative to glide not using pmonoartic because of a bug in pmonoartic

(123) play 3 synths together (degree = #[1,2,3])
or play a chord (135) where the 1st digit = degree, and others relative degree
any of the digits may be random.. and all other modifiers apply too
or play 3 samples together (maybe amp should be lowered?) hmm..
or strum!

setup: Pmini.kit = [$_, "bd", 1, $=, "sn", 2, $^, "hh", 3,.. etc]
characters useable in "K" spec: #^*-_=':;,.\
Pmini("K _'<^[^^]>(_') A 2") if "K" is used, then sound arg is ignored

******************************************************************/

Pmini {
	classvar playbuf_synthdef=\playbuf, playbuf1_synthdef=\playbuf1;

	// A "cycle" (1 traversal of the tree) equals 1 bar and has bpb beats
	var <bpb = 4;

	*new { |in_bpb, sound="", spec=""| ^super.new.init(in_bpb, sound, spec); }

	init { |in_bpb, sound, spec|
		// the optional arguments start from the left side here
		if(spec.asString.size <= 0, { spec = sound; sound=in_bpb; in_bpb=4; });
		if(spec.asString.size <= 0, { spec = sound; sound=""; });
		bpb = in_bpb.asFloat;

		if(spec.toLower != spec, { ^this.return_pbind(sound, spec) });

		^this.return_pattern(sound, spec);
	}

	return_pattern { |sound, spec|
		var root = PMRoot(spec);

		root.post;

		case

		// [\dur, \degree]
		{ sound == "dd" } { ^Pn(Plazy({
			var event = root.next_event(bpb);

			Pseq([event.dur, event.degree], 1)
		}));
		}

		// \dur
		{ sound == "d" } { ^Pn(Plazy({ root.next_event(bpb).dur })) }

		// \degree
		{	^Pn(Plazy({ root.next_event(bpb).degree })) }
	}

	return_pbind { |sound, spec|
		var part, parts, durations_part, pb, mid;

		// split spec in parts and determine which one defines the durations.
		// each uppercase char is surrounded by 2 spaces.
		// add one extra space at the end, because then you can just
		// remove the first and last space char of every part after parsing.
		spec = spec ++ " ";

		parts = Dictionary.new;
		spec.asString.do { |ch|
			case
			{ ch.isAlpha.and(ch.isUpper) } { part = ch.toLower.asSymbol }
			{ part.isNil } { } // wait for the first uppercase char
			{ ch == $+ } { durations_part = part }
			{ parts.put(part, parts.atFail(part, "") ++ ch.asString); };
			if(durations_part.isNil.and(part.notNil), { durations_part = part; });
		};

		parts = parts.collect { |spec| spec.rotate(1).copyToEnd(2) };
		sound = sound.asSymbol;

		if(Library.at(\samples, sound).notNil, {
			pb = Pbind.new(
				\instrument, playbuf_synthdef,
				\playing, \sample,
			);
			parts = parts.collect { |spec| PMRoot(spec, \sample) };
		}, {
			pb = Pbind.new(
				\instrument, sound,
				\playing, \synth,
			);
			parts = parts.collect { |spec| PMRoot(spec, \synth) };
		});

		//if(parts.includesKey(\n), { parts.at(\n).post });

		pb.patternpairs_(pb.patternpairs ++ [
			\dur, Pfunc({ |ev|
				var degree, dur, sustain;
				var events = parts.collect { |parser| parser.next_event(bpb) };

				//events.at(\n).post;

				dur = events.at(durations_part).dur.asFloat;
				sustain = events.at(durations_part).sustain.asFloat;

				degree = events.at(\n).degree.asInteger;
				events.do { |event| if(event.type == \rest, { degree = \rest }) };
				ev.put(\degree, degree);

				if(degree.isInteger, {

					if(ev[\playing] == \sample, {
						ev.put(\bufnum, Library.at(\samples, sound).wrapAt(degree).bufnum);
						ev.put(\rate, (events.at(\n).up - events.at(\n).down).midiratio);
					});

					if(ev[\playing] == \synth, {

						if(events.at(\n).type == \note, {
							ev.put(\callback, { |ev2| mid = ev2.id.at(0) });
							ev.put(\sustain, sustain);
						});

						if(events.at(\n).type == \set, {
							ev.put(\type, \set);
							ev.put(\id, mid );
							ev.put(\sustain, (dur * 0.5)); // use this for freq varlag
						});

						// sharp/flat
						ev.put(
							\degree,
							(events.at(\n).up - events.at(\n).down) * 0.1 + degree
						);
					});

					if(events.includesKey(\o), {
						ev.put(\octave, events.at(\o).degree.asInteger.clip(2,8));
					});

					if(events.includesKey(\a), {
						// could use \db key here, 0db = 1, 20db = (-20)db etc
						ev.put(\amp, events.at(\a).degree.asInteger.clip(0,8) / 8);
					});

					if(events.includesKey(\p), {
						ev.put(\pan, events.at(\p).degree.asInteger.clip(0,8) / 4 - 1);
					});
				});

				dur
			})
		]);

		^pb;
	}
}

PMRoot : PMNested {
	var index = 0, str, cycle, events, playing;

	*new { |spec, in_playing = \synth|
		^super.new.init(spec.asString, in_playing);
	}

	init { |spec, in_playing|
		str = spec;
		cycle = -1;
		events = List.new;
		playing = in_playing; // \sample or \synth, makes a difference for parser
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
			{ ch == $x } {
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
			{ ch == $' } {
				if(playing == \sample, { parsing = \up }, {
					if(node.notNil, { node.up_(1) });
					parsing = \value;
				});
			}
			{ ch == $, } {
				if(playing == \sample, { parsing = \down }, {
					if(node.notNil, { node.down_(1) });
					parsing = \value;
				});
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
				if(node.notNil.and("23456789?".contains(ch)), {
					node.faster_(ch);
				});
				parsing = \value;
			}
			{ parsing == \slower } {
				if(node.notNil.and("23456789?".contains(ch)), {
					node.slower_(ch);
				});
				parsing = \value;
			}
			{ parsing == \up } {
				if(node.notNil.and("123456789?".contains(ch)), {
					node.up_(ch);
				});
				parsing = \value;
			}
			{ parsing == \down } {
				if(node.notNil.and("123456789?".contains(ch)), {
					node.down_(ch);
				});
				parsing = \value;
			}
			{ parsing == \repeat } {
				if(node.notNil.and("23456789".contains(ch)), {
					(ch.asString.asInteger -1).do {
						node = currentNode.addChild(node.clone);
					};
				});
				parsing = \value;
			}
			{ }; // default no action
		};

		^this; // you are the root node of the tree, so return yourself
	}

	next_event { | bpb |
		var event, index, type, sustain;

		if(events.size < 1, { this.make_more_events(bpb); });

		event = events.removeAt(0);

		//("next_event(" + event.type + ")").postln;

		if(event.type == \space, { event.type_(\rest); ^event });
		if(event.type == \glide, { event.type_(\set); ^event });
		if(event.type == \rest, { ^event });

		// we have a \note here

		// make_more_events until you encounter the next \note or \rest
		// and calculate \sustain for the current \note by summing the \dur
		// values of all the \space and \glide events up until the next \note.

		// after calculating this \sustain, then you can return the current
		// \note or \rest event to be played.

		sustain = event.dur * 0.8;

		if(events.size < 1, { this.make_more_events(bpb) });

		index = 0;
		type = events.at(index).type;

		while { (type != \note).and(type != \rest) }
		{
			//["--", index, type, events.at(index).dur, sustain].postln;
			sustain = sustain + events.at(index).dur;
			index = index + 1;
			if(events.size <= index, { this.make_more_events(bpb) });
			type = events.at(index).type;
		};

		event.sustain_(sustain);

		^event;
	}

	make_more_events { | bpb |
		cycle = cycle + 1;
		events.addAll(super.get_events(cycle, bpb));
	}
}

PMNote : PMNode {
	do_traverse { |cycle, dur|
		^List.newFrom([	PMEvent.newFromPMNode(this, \note, this.alter(dur), value) ]);
	}
}

PMRest : PMNode {
	do_traverse { |cycle, dur|
		^List.newFrom([ PMEvent.newFromPMNode(this, \rest, this.alter(dur))] );
	}
}

PMSpace : PMNode {
	do_traverse { |cycle, dur|
		^List.newFrom([ PMEvent.newFromPMNode(this, \space, this.alter(dur))] );
	}
}

PMGlide : PMNode {
	do_traverse { |cycle, dur|
		^List.newFrom([
			PMEvent.newFromPMNode(this, \glide, this.alter(dur), value)
		]);
	}
}

PMNested : PMNode {
	do_traverse { |cycle, dur|
		var d, result = List.new;

		d = this.alter(dur) / children.size;
		children.do { |node| result.addAll(node.get_events(cycle, d)) }
		^result;
	}

	clone {
		var clone = super.clone;
		children.do { |child| clone.addChild(child.clone) };
		^clone;
	}
}

PMTurns : PMNode {
	do_traverse { |cycle, dur|
		var node = children.wrapAt(cycle);
		^List.newFrom(node.get_events(cycle, this.alter(dur)));
	}

	clone {
		var clone = super.clone;
		children.do { |child| clone.addChild(child.clone) };
		^clone;
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
					remaining_events.addAll(this.do_traverse(cycle, dur));
					if(remaining_events.size <= 0, { stop = 1; }); // prevent endless loop
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
			this.class.name,
			slower.asString,
			faster.asString,
			up.asString,
			down.asString,
			value.asString
		)
	}

	alter { |dur|
		case
		{ "23456789".contains(slower) } { dur = dur * slower.asString.asInteger }
		{ slower == $? } { dur = dur * (2 + 7.rand) }
		{};

		case
		{ "23456789".contains(faster) } { dur = dur / faster.asString.asInteger }
		{ faster == $? } { dur = dur / (2 + 7.rand) }
		{};

		^dur;
	}
}

// random value ? is deferred until inside the constructor of this class
PMEvent {
	var <>type, <>dur, <>degree=0, <>up=0, <>down=0, <>sustain=0;

	*new { | type, dur, degree=$0, up=$0, down=$0 |
		^super.newCopyArgs(
			type,
			dur.asFloat,
			case { degree == $? } { 9.rand } { degree.asString.asInteger },
			case { up == $? } { 1 + 8.rand } { up.asString.asInteger },
			case { down == $? } { 1 + 8.rand } { down.asString.asInteger }
		);
	}

	*newFromPMNode { | n, type, dur, degree=$0 |
		^PMEvent.new(type, dur, degree, n.up, n.down);
	}

	*newFromPMEvent { | event, dur |
		^super.newCopyArgs(
			event.type,
			dur.asFloat,
			case { event.degree == $? } { 9.rand } { event.degree.asString.asInteger },
			case { event.up == $? } { 1 + 8.rand } { event.up.asString.asInteger },
			case { event.down == $? } { 1 + 8.rand } { event.down.asString.asInteger }
		);
	}

	post {
		format(
			"% % % % % % %",
			this.class.name,
			type,
			dur,
			sustain,
			degree,
			up,
			down
		).postln;

		^this;
	}
}
