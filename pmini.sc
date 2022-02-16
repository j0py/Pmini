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
X samples: ', + 1 (optional) digit (0..9) rate = rate * / digit.midiratio
synthdef: ', + 1 (optional) digit (0..9) <digit> octaves up/down

implement !, + 1 (optional) digit (0..9) repeat step

* and / always expect 1 digit (*2/3 == /1.5)
(then putting a "|" after to end the step is not needed anymore)

skip the spaces around the uppercase letters?

******************************************************************/

Pmini {
  classvar playbuf_synthdef=\playbuf, playbuf1_synthdef=\playbuf1;

	// A "cycle" (1 traversal of the tree) has a total \dur of 1 (a beat).
	// But the returned \dur values are multiplied by the number of beats
	// per bar, so that 1 "cycle" equals one "bar" in the music.
	var <beatsPerBar = 4;

	*new { |bpb, sound="", spec=""| ^super.new.init(bpb, sound, spec); }

	init { |bpb, sound, spec|
		// the optional arguments start from the left side here
		if(spec.asString.size <= 0, { spec = sound; sound=bpb; bpb=4; });
		if(spec.asString.size <= 0, { spec = sound; sound=""; });
		beatsPerBar = bpb.asFloat;

		if(spec.toLower != spec, { ^this.return_pbind(sound, spec) });

		^this.return_pattern(sound, spec);
	}

	return_pattern { |sound, spec|
		var root = PMRoot(spec);

		case

		// [\dur, \degree]
		{ sound == "dd" } { ^Pn(Plazy({
			var event = root.next_event;

			Pseq([event.dur * beatsPerBar, event.degree], 1)
		}));
		}

		// \dur
		{ sound == "d" } { ^Pn(Plazy({ root.next_event.dur * beatsPerBar })) }

		// \degree
		{	^Pn(Plazy({ root.next_event.degree })) }
	}

	return_pbind { |sound, spec|
		var part, parts, durations_part, pb;

		// split spec in parts and determine which one defined the durations.
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

		parts = parts.collect { |spec| PMRoot(spec.rotate(1).copyToEnd(2)) };
		sound = sound.asSymbol;

		if(Library.at(\samples, sound).notNil, {
			pb = Pbind.new(
				\instrument, playbuf_synthdef,
				\playing, \sample,
			);
		}, {
			pb = PmonoArtic(sound,
				\playing, \synth,
			);
		});

		pb.patternpairs_(pb.patternpairs ++ [
			\dur, Pfunc({ |ev|
				var degree, events = parts.collect { |parser| parser.next_event };

				degree = events.at(\n).degree.asInteger;
				events.do { |event| if(event.type == \rest, { degree = \rest }) };
				ev.put(\degree, degree);

				if(degree.isInteger, {
					if(ev[\playing] == \sample, {
						ev.put(\bufnum, Library.at(\samples, sound).wrapAt(degree).bufnum);
						ev.put(\rate, (events.at(\n).up - events.at(\n).down).midiratio);
					});

					if(ev[\playing] == \synth, {
						ev.put(\legato, events.at(\n).legato);
						ev.put(\octave, 5 + (events.at(\n).up - events.at(\n).down));
					});

					if(events.includesKey(\a), {
						ev.put(\amp, events.at(\a).degree.asInteger.clip(0,8) / 8);
					});

					if(events.includesKey(\p), {
						ev.put(\pan, events.at(\p).degree.asInteger.clip(0,8) / 4 - 1);
					});
				});

				events.at(durations_part).dur.asFloat * beatsPerBar; // \dur
			})
		]);

		^pb;
	}
}

PMRoot : PMNested {
	var index = 0, str, cycle, events;

	*new { |spec| ^super.new.init(spec.asString); }

	init { |spec| str = spec; cycle = -1; events = List.new; ^this.parse(this) }

	parse { |currentNode|
		var node, stretch="", faster="", slower="", parsing=\value;

		while
		{ index < str.size } {
			var ch = str.at(index);
			index = index + 1;

			if(" <[|".contains(ch.asString), {
				// this char will start recursiveness or end a node
				// we must save stuff for the current node before moving on
				// this code existed on 3 places below, so doing it this way,
				// the DRY principle is followed better.

				parsing = \value; // next char starts a new node
				if(node.notNil, {
					if(faster.size > 0, { node.faster_(faster.asFloat) });
					faster = "";
					if(slower.size > 0, { node.slower_(slower.asFloat) });
					slower = "";
					if(stretch.size > 0, { node.stretch_(stretch.asFloat) });
					stretch = "";
				});
			});

			case
			{ ch == $x } {
				node = PMRest.new;
				currentNode.addChild(node);
			}
			{ ch == $  } {
				node = PMSpace.new;
				currentNode.addChild(node);
			}
			{ ch == $< } {
				node = PMTurns.new;
				this.parse(node);
				currentNode.addChild(node);
			}
			{ ch == $> } { ^this }
			{ ch == $[ } {
				node = PMNested.new;
				this.parse(node);
				currentNode.addChild(node);
			}
			{ ch == $] } { ^this }
			{ ch == $| } { } // has already been done above
			{ ch == $~ } { if(node.notNil, { node.glide_(1) }) }
			{ ch == $@ } { parsing = \stretch; }
			{ ch == $* } { parsing = \faster; }
			{ ch == $/ } { parsing = \slower; }
			{ ch == $' } { parsing = \up; }
			{ ch == $, } { parsing = \down; }

			{ parsing == \value } {
				// start new node and parse the value (a 1 digit number)
				node = PMNote.new;
				node.value_(ch.asString);
				currentNode.addChild(node);
			}
			{ parsing == \stretch } {
				if(node.notNil.and(".0123456789".contains(ch)), {
					stretch = stretch ++ ch.asString;
				});
			}
			{ parsing == \faster } {
				if(node.notNil.and(".0123456789".contains(ch)), {
					faster = faster ++ ch.asString;
				});
			}
			{ parsing == \slower } {
				if(node.notNil.and(".0123456789".contains(ch)), {
					slower = slower ++ ch.asString;
				});
			}
			{ parsing == \up } {
				if(node.notNil.and("0123456789".contains(ch)), {
					node.up_(ch.asString.asInteger);
				});
				parsing = \value;
			}
			{ parsing == \down } {
				if(node.notNil.and("0123456789".contains(ch)), {
					node.down_(ch.asString.asInteger);
				});
				parsing = \value;
			}
			{ }; // default no action
		};

		if(node.notNil, {
			if(faster.size > 0, { node.faster_(faster.asFloat) });
			faster = "";
			if(slower.size > 0, { node.slower_(slower.asFloat) });
			slower = "";
			if(stretch.size > 0, { node.stretch_(stretch.asFloat) });
			stretch = "";
		});

		^this; // you are the root node of the tree, so return yourself
	}

	next_event {
		var event;

		if(events.size < 1, { this.make_more_events; });

		event = events.removeAt(0);

		// if we start with a \space: just return a \rest
		if(event.type == \space, { event.type_(\rest); ^event });

		// \note or a \rest: add the \dur(s) of following \space(s)
		if(events.size < 1, { this.make_more_events; });
		while { events.at(0).type == \space }
		{
			event.dur_(event.dur + events.at(0).dur);
			events.removeAt(0);
			if(events.size < 1, { this.make_more_events; });
		};

		^event;
	}

	make_more_events {
		cycle = cycle + 1;
		events.addAll(super.get_events(cycle, 1));
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

PMNested : PMNode {
	do_traverse { |cycle, dur|
		var d, result = List.new;

		d = this.alter(dur) / children.size;
		children.do { |node| result.addAll(node.get_events(cycle, d)) }
		^result;
	}
}

PMTurns : PMNode {
	do_traverse { |cycle, dur|
		var node = children.wrapAt(cycle);
		^List.newFrom(node.get_events(cycle, this.alter(dur)));
	}
}

PMNode {
	var <>parent, <children, <>prev, <>next, <>value;
	var <>stretch=1.0, <>faster=1.0, <>slower=1.0, <>glide=0;
	var <>up=0, <>down=0;
	var remaining_events, remain = 0;

	*new { ^super.new.initPMNode; }

	initPMNode {
		children = List.new;
		remaining_events = List.new;
		^this
	}

	addChild { |node|
		node.parent_(this);
		if(children.size > 0, {
			children.last.next_(node);
			node.prev_(children.last);
		});

		children.add(node);
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
					result.add(PMEvent(\space, remain, 0));
					duration = duration - remain;
					if(duration < 0.0001, { duration = 0; });
					remain = 0;
				}, {
					result.add(PMEvent(\space, duration, 0));
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
			"% % % % \"%\"",
			this.class.name,
			stretch / faster * slower,
			up,
			down,
			value.asString
		)
	}

	alter { |dur| ^dur * stretch / faster * slower }
}

PMEvent {
	var <>type, <>dur, <>degree=0, <>glide=0, <>up=0, <>down=0;

	*new { | type, dur, degree |
		^super.newCopyArgs(type, dur.asFloat, degree.asInteger);
	}

	*newFromPMNode { | pmnode, type, dur, degree=0 |
		^super.newCopyArgs(type, dur.asFloat, degree.asInteger, pmnode.glide.asInteger, pmnode.up, pmnode.down);
	}

	*newFromPMEvent { | event, dur |
		^super.newCopyArgs(event.type, dur.asFloat, event.degree, event.glide, event.up, event.down);
	}

	legato { case { glide <= 0 } { ^0.8 } { ^1.0 } }
}
