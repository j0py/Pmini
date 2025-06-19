/*
Takes a mini-notation string as input.
Returns a pattern that can be used in a Pbind.
The pattern has 5 values:

\trig : 1 should trigger a note, 0 should not
\delta: how long to wait before processing the next step
\dur  : value to calculate the sustain for a step
\str  : string value for a step
\num  : integer value for a step

Example:
Pbind(
    [\trig, \delta, \dur, \str, \num], Pmini("1 2 3 4"),
	\degree, Pfunc({ |e| if(e.trig > 0) { e.str.asInteger } { \rest } }),
)

*/

Pmini {
	*new { |mini_notation| ^JSMN.pmini(mini_notation) }
}

// (Pm)ini(b)ind
//
// example:
//
// s.boot
// x = Pmb(\mini, "1 <2 5 _ 3> 3 4").play
// x.stop
//
Pmb : Pbind {
	*new { arg ... pairs;
		pairs.asDict.at(\mini) !? { |pattern|
			var pair = [
				[\trig, \delta, \dur, \str, \num],
				Pmini(pattern),
				\degree, Pfunc({ |e|
					if(e.trig <= 0) { \rest } { e.str.asFloat }
				})
			];
			pairs = pair ++ pairs;
		}
		
		^super.new.patternpairs_(pairs);
	}
}

JSMN {
	// this class is the main entry point

	// made public to be able to test JSMNReader
	*chars { |str|
		var ch, reader = JSMNReader(str);
		while { (ch = reader.next).notNil } {
			while { 0.5.coin } { ch = reader.prev };
			"%".format(ch).postln
		};
	}

	// made public to be able to test JSMNTokenizer
	*tokens { |str|
		try {
			var tokens = JSMNTokenizer.parse(JSMNReader(str));
			// directly returning the above cancels the try / catch..
			^tokens.collect({ |token| "\n" ++ token.asString });
		} { |err|
			err.errorString.postln;
		}
	}

	// creates a tree of nodes by consuming all the available tokens
	*consume { |str|
		try {
			var tokens = JSMNTokenizer.parse(JSMNReader(str));
			var root = JSMNGroup("root");
			var node = root;

			tokens.do { |token|
				//"% consumes %".format(node.value, token).postln;
				node = node.consume(token)
			};

			^root;
		} { |err|
			err.errorString.postln;
		}
	}

	// made public to be able to test the consume function
	*tree1 { |str|
		try {
			JSMN.consume(str).log;
		} { |err|
			err.errorString.postln;
		}
	}

	// build the tree and re-structure it so that it becomes useable
	// for creating cycles
	*tree { |str|
		try {
			^JSMN.consume(str)
			.attachModifiers
			.detectSubsequences;
			//.log;
		} { |err|
			err.errorString.postln;
		}
	}

	// return n cycles as arrays of [\trig, \delta, \str, \num] arrays
	*steps { |str, n=1|
		var root = JSMN.consume(str);
		root
		.attachModifiers
		.detectSubsequences
		.log;
		n.do { |i|
			"cycle %".format(i).postln;
			root.steps.do { |step| step.postln };
		};
		^"";
	}

	*pmini { |str|
		var pattern = JSMNPattern(str);
		
		^Pn(Plazy({	Pseq(pattern.steps, 1) }), inf)
	}
}

JSMNPattern {
	var root;

	*new { |str| ^super.newCopyArgs(JSMN.tree(str)) }

	steps {	^root.steps.collect({ |step| step.asArray }) }
}

JSMNNode {
	var <value, <>children, <>modifiers, queue, <>euclid, <>parent;

	*new { |token|
		^super.newCopyArgs(token.value, List.new, List.new, List.new);
	}
	
	add { |node| children.add(node); ^node.parent_(this) }

	last { ^children.last }

	log { |indent=""|
		"%%".format(indent, value).postln;
		children.do { |child| child.log(indent ++ "--") };
		modifiers.do { |child| child.log(indent ++ "--") };
		euclid !? { euclid.log(indent ++ "--") };
	}

	attachModifiers {
		var last, newchildren = List.new;
		
		children.do { |child|
			case
			{ child.class == JSMNModifier }
			{ last !? { last.modifiers.add(child) } }
			{ child.class == JSMNEuclid }
			{ last !? { last.euclid = child } }
			{
				last = child;
				newchildren.add(child)
			};

			child.attachModifiers;
		};

		children = newchildren;
	}

	detectSubsequences {
		euclid !? { euclid.detectSubsequences };
		children.do { |child| child.detectSubsequences };
	}

    // Patternable modifiers:
    //
	// when you ask this, you need to know where you are in the step,
	// on what time (0..1), so that the correct value from the
	// modifier steps/cycle can be obtained.
	//
	// Some modifiers influence "where you are in the cycle" (*/@!).
	// For these modifiers, just take the value from the first step.
	get { |modifier, default=1|
		modifiers.do { |ch|
			if(ch.value === modifier) {
				ch.steps.do { |step|
					//if(step.str != default) {
					//	"% % %".format(value, modifier, step.str).postln
					//};

					// return the value for the first step??
					^step.str
				}
			}
		};

		^default;
	}

	steps { |delta=1|
		var d = delta / this.get($*, 1).asFloat * this.get($/, 1).asFloat;
		var steps = List.new;
		//"% steps (%)".format(value, this.class).postln;
		while { delta >= 0.0001 } {
			var stop = 10;
			while { queue.size < 2 } { this.fillqueue(d) };
			if(value == "root") {
				while { (queue.at(1).str == "_") and: (stop > 0) } {
					var step = queue.removeAt(1);
					queue.at(0).delta = queue.at(0).delta + step.delta;
					queue.at(0).dur = queue.at(0).dur + step.dur;
					while { queue.size < 2 } { this.fillqueue(d) };
					stop = stop - 1;
				};
			};

			if(queue.at(0).delta <= (delta + 0.0001)) {
				var step = queue.removeAt(0);
				steps.add(step);
				delta = delta - step.delta;
			} {
				var short = queue.at(0).delta - delta;
				steps.add(queue.at(0).copy.delta_(delta));
				delta = 0;
				// queue.at(0).delta_(short).trig_(0); JST 2025-05-24
				queue.at(0).delta_(short).dur_(short).trig_(0);
			};

            // JST 20250424
            if(steps.last.str == "_") { steps.last.trig = 0 };
            if(steps.last.str == "~") { steps.last.trig = 0 };
		};

		^steps;
	}

	fillqueue { |delta|
		case
		{ euclid.isNil }
		{ this.fill(delta) }
		{
			var slots = euclid.slots(delta);
			var sum = slots.sum;
			
			slots.do { |slot| this.fill(delta * slot / sum) }
		}
	}
	
	// @override
	fill { |delta| queue.add(JSMNStep(1, delta, value, nil)) }
}

JSMNTurn : JSMNGroup {
	var turn;
	
	fill { |delta|
		var indexes = List.new;

		turn = (turn ? -1) + 1;

		children.do { |ch, index|
			ch.get($!, 1).asInteger.do { indexes.add(index) }
		};

		queue.addAll(children.at(indexes.wrapAt(turn)).steps(delta));
	}
}

/*
The Euclidean Algorithm Generates Traditional Musical Rhythms by Toussaint
(2,5) : A thirteenth century Persian rhythm called Khafif-e-ramal.
(3,4) : The archetypal pattern of the Cumbia from Colombia, as well as a Calypso rhythm from Trinidad.
(3,5,2) : Another thirteenth century Persian rhythm by the name of Khafif-e-ramal, as well as a Rumanian folk-dance rhythm.
(3,7) : A Ruchenitza rhythm used in a Bulgarian folk-dance.
(3,8) : The Cuban tresillo pattern.
(4,7) : Another Ruchenitza Bulgarian folk-dance rhythm.
(4,9) : The Aksak rhythm of Turkey.
(4,11) : The metric pattern used by Frank Zappa in his piece titled Outside Now.
(5,6) : Yields the York-Samai pattern, a popular Arab rhythm.
(5,7) : The Nawakhat pattern, another popular Arab rhythm.
(5,8) : The Cuban cinquillo pattern.
(5,9) : A popular Arab rhythm called Agsag-Samai.
(5,11) : The metric pattern used by Moussorgsky in Pictures at an Exhibition.
(5,12) : The Venda clapping pattern of a South African children’s song.
(5,16) : The Bossa-Nova rhythm necklace of Brazil.
(7,8) : A typical rhythm played on the Bendir (frame drum).
(7,12) : A common West African bell pattern.
(7,16,14) : A Samba rhythm necklace from Brazil.
(9,16) : A rhythm necklace used in the Central African Republic.
(11,24,14) : A rhythm necklace of the Aka Pygmies of Central Africa.
(13,24,5) : Another rhythm necklace of the Aka Pygmies of the upper Sangha.
*/
JSMNEuclid : JSMNGroup {
	slots { |delta|
		var xval, yval, rval, sum, slots, left_over, spread;

		this.children.do { |child, i|
			var val = child.steps(delta).first.str.asInteger;
			if(i == 0) { xval = val };
			if(i == 1) { yval = val };
			if(i == 2) { rval = val };
		};

		// https://www.lawtonhall.com/blog/euclidean-rhythms-pt1
		// distribute y things over x slots rather evenly (y > x)
		
		// 1: fill all slots equally with as much things as possible
		slots = Array.fill(xval, yval.div(xval));

		// 2: calculate how many things are left over
		left_over = yval - (slots[0] * xval);
		
		// 3: distribute the leftover things evenly, adding 1 to some slots
		spread = xval.div(left_over);
		left_over.do { |i| i = i * spread; slots[i] = slots[i] + 1 };

		// 4: rotate
		^slots.rotate(rval ? 0);
	}
}

JSMNGroup : JSMNNode {
	var <separator, turn;

	fill { |delta|
		case
		{ value == ${ }
		{
			var wrap = this.get($%, 1).asInteger;

			case
			{ separator == $, }
			{ this.fill_polymetric(delta) }
			{ wrap > 1 }
			{
				wrap.do { |i|
					queue.addAll(children.wrapAt(i).steps(delta / wrap))
				}
			}
			{ this.fill_normal(delta) }
		}
		{
			case
			{ separator == $, }
			{ this.fill_parallel(delta) }
			{ separator == $| }
			{ this.fill_random(delta) }
			{ this.fill_normal(delta) }
		}
	}

	/*
JSMN.steps("{1 2, [3 4] 5 7, 6}")
JSMN.steps("{1 2 [3 4]}%5")
	*/
	
	fill_polymetric { |delta|
		var time, pq = PriorityQueue.new;
		var steps = children.first.steps(delta);

		time = 0;
		steps.do { |step|
			pq.put(time, step);
			
			turn = (turn ? -1) + 1;
			children.do { |child, i|
				if(i > 0) {
					var t = time;
					child.children.wrapAt(turn).steps(step.delta).do { |x|
						pq.put(t, x);
						t = t + x.delta;
					};
				}
			};

			time = time + step.delta;
		};

		time = -1;
		while { pq.notEmpty } {
			var step, top = pq.topPriority;

			if((time < 0) and: (top > 0.0001)) {
				queue.add(JSMNStep(0, top, "~~"));
			};

			time = top;
			step = pq.pop;

			// set delta, but keep dur as is!
			if(pq.isEmpty) {
				step.delta = delta - time;
			} {
				step.delta = pq.topPriority - time;
			};
			
			queue.add(step);
		}		
	}
	
	fill_random { |delta|
		queue.addAll(children.choose.steps(delta))
	}

	fill_parallel { |delta|
		var time, queue2 = PriorityQueue.new;

		children.do { |child|
			time = 0;
			child.steps(delta).do { |step|
				if(step.trig > 0) { queue2.put(time, step) };
				time = time + step.delta;
			}
		};

		time = -1;
		while { queue2.notEmpty } {
			var step, top = queue2.topPriority;

			if((time < 0) and: (top > 0.0001)) {
				queue.add(JSMNStep(0, top, "~~"));
			};

			time = top;
			step = queue2.pop;

			// set delta, but keep dur as is!
			if(queue2.isEmpty) {
				step.delta = delta - time;
			} {
				step.delta = queue2.topPriority - time;
			};
			
			queue.add(step);
		};
	}
	
	fill_normal { |delta|
		var indexes = List.new;
		var weights = List.new;
		var sum, child_steps, last_index;

		//"% fill_normal".format(value).postln;
		
		// determine weights
		children.do { |ch, i|
			var repeat = ch.get($!, 1).asInteger;
			var weight = ch.get($@, 1).asFloat;

			repeat.do {
				indexes.add(i);
				weights.add(weight);
			}
		};

		// hmmmm..
		if((value == "root") and: (weights.size > 1)) {
			// transfer weights between children
			for(weights.size - 1, 1) { |i|
				var child = children.at(indexes.at(i));
				
				if(child.value == "_") {
					weights.put(i - 1, weights.at(i - 1) + weights.at(i));
					weights.put(i, 0);
				}
			};
		};

		sum = weights.sum;

		last_index = -1;
		weights.do { |weight, i|
			var factor, index = indexes.at(i);
			if(index != last_index) {
				child_steps = children.at(index).steps(1);
				last_index = index;
			};

			if(weight > 0.0001) {
				factor = delta * weight / sum;
				queue.addAll(
					child_steps.deepCopy.collect { |step|
						step.delta_(step.delta * factor);
						step.dur_(step.dur * factor);
					}
				)
			}
		}
	}

	consume { |token|
		case
		{ token.isSeparator } { this.add(JSMNSeparator(token)) }
		{ token.isTurnOpener } { ^this.add(JSMNTurn(token)) }
		{ token.isEuclidOpener } {
			//"% ingroup iseu %".format(value, token).postln;
			^this.add(JSMNEuclid(token))
		}
		{ token.isGroupOpener } { ^this.add(JSMNGroup(token)) }
		{ token.isGroupCloser } { ^this.parent }
		{ token.isModifier } { ^this.add(JSMNModifier(token)) }
		{ token.isString } { this.add(JSMNData(token)) } // this one last
		{ }
	}

	log { |indent=""|
		var sep="";
		separator !? { sep = " %".format(separator.asString.quote) };
		"%%%".format(indent, value, sep).postln;
		children.do { |child| child.log(indent ++ "--") };
		modifiers.do { |child| child.log(indent ++ "--") };
		euclid !? { euclid.log(indent ++ "--") };
	}

	// if a separator was used, chop this group into subgroups
	detectSubsequences {
		var subgroups = List.new;

		subgroups.add(List.new);
		
		children.do { |child|
			if(child.class == JSMNSeparator) {
				// first encountered separator will be 'our' separator
				separator = separator ? child.value;
				if(child.value == separator) {
					subgroups.add(List.new)
				} {
					subgroups.last.add(child);
				}
            } {
				subgroups.last.add(child);
			}
		};

		if(subgroups.size > 1) {
			var newchildren = List.new;
			subgroups.do { |subgroup|
				//if(subgroup.size > 1) {
					newchildren.add(JSMNGroup(JSMNToken($[,0)));
					subgroup.do { |child| newchildren.last.add(child) };
				//} {
				//	newchildren.add(subgroup.first);
				//}
			};
			children = newchildren;
		};

		children.do { |child| child.detectSubsequences };
	}
}

JSMNModifier : JSMNNode {
	consume { |token|
		case
		{ token.isString } {
			if(children.size <= 0) {
				this.add(JSMNData(token))
			} {
				^this.parent.consume(token)
			}
		}
		{ token.isTurnOpener } { ^this.add(JSMNTurn(token)) }
		{ token.isEuclidOpener } {
			//"% inmod iseu %".format(value, token).postln;
			^this.parent.consume(token)
			//^this.add(JSMNEuclid(token))
		}
		{ token.isGroupOpener } { ^this.add(JSMNGroup(token)) }
		{ ^this.parent.consume(token) }
	}

	fill { |delta| queue.addAll(children.at(0).steps(delta)) }
}

JSMNData : JSMNNode {
	consume { |token| ^this.parent.consume(token) }

	fill { |delta|
    // JST 2025-04-02:
    // if the $<hex> modifier was used, then you can create
    // multiple steps at this point and add them to the
    // queue (with smaller delta values that addup to the
    // original delta of course).
    queue.add(JSMNStep(1, delta, value, this.get($:, nil)));
  }
}

JSMNSeparator : JSMNNode {
	consume { |token| ^this.parent.consume(token) }
}

JSMNStep {
	var <>trig, <>delta, <>str, <>num, <>dur;

	*new { |trig, delta, str, num|
		^super.newCopyArgs(trig, delta, str, num, delta)
	}

	asArray { ^[ trig, delta, dur, str, num] }
	
	printOn { |stream|
		stream << "Step (%, %, %, %, %)".format(
			trig, delta.round(0.01), dur.round(0.01), str, num
		);
	}
}

JSMNCycle {
	var env, list;

	fill { |nodes|
		var deltas = List.new;
		list = List.new.addAll(nodes);
		list.do { |node| deltas.add(node.delta) };
		env = Env.new((0..nodes.size), deltas / deltas.sum, \step);
	}

	nodeAt { |time| ^list.at(env.at(time.clip(0,1))) }
}

JSMNReader {
	var str, <index = 0;
	*new { |str| ^super.newCopyArgs(str) }
	next { str.at(index) !? { |ch| index = index + 1; ^ch } ?? { ^nil } }
	prev { if(index <= 0) { ^nil };	index = index - 1; ^str.at(index) }
	peek { |n| ^str.at(index + n - 1) }
}
	
JSMNTokenizer {
	*parse { |reader|
		var ch, tokens=List.new, part, partindex;

		while { (ch = reader.next).notNil } {
			case
			{ ch === $  }
			{
				part !? { tokens.add(JSMNToken(part, partindex)) };
				part = nil;

				// detect the Marking your feet (" . ") separator
				if((reader.peek(1) === $.) and: (reader.peek(2) === $ )) {
					tokens.add(JSMNToken(" . ", reader.index));
					reader.next;
					reader.next;
				} {
					tokens.add(JSMNToken(ch, reader.index));
				}
			}
			{ "~_".contains(ch) }
			{
				case
				{ part.notNil } { part = part ++ ch }
				{ tokens.add(JSMNToken(ch.asString, reader.index)) }
			}
			//{ "!@#$%^&*()+{}[]:\"':;/?<>,|".contains(ch) }
			{ "!@%^&*()+{}[]:\"':/?<>,|".contains(ch) }
			{
				if(part.notNil) {
					tokens.add(JSMNToken(part.asString, partindex));
					part = nil;
				};
				
				tokens.add(JSMNToken(ch, reader.index));
			} {
				part ?? { partindex = reader.index;	part = "" };
				part = part ++ ch;
			}
		};
		part !? { tokens.add(JSMNToken(part, partindex)) };

		^tokens.asArray;
	}
}

JSMNToken {
	var <>value, <>index, <>isModifier, <>isGroupOpener, <>isGroupCloser,
	<>isString, <>isSeparator, <>isTurnOpener, <>isEuclidOpener;
	
	*new { |value, index|
		var instance = super.newCopyArgs(value, index);
		instance.isModifier_("/*@%:?!".contains(value)); // maybe add $
		instance.isTurnOpener_("<".contains(value));
		instance.isEuclidOpener_("(".contains(value));
		instance.isGroupOpener_("[{".contains(value));
		instance.isGroupCloser_("]>})".contains(value));
		instance.isSeparator_("|,".contains(value) or: (" . " == value));
		instance.isString_(value.isString);
		^instance;
	}

	printOn { |stream|
		stream << "% % % %".format(
			value.asString.quote,
			index,
			value.class,
			isString
		)
	}
}

