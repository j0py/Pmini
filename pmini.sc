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
		.detectSubsequence
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
				if(step.str == "_") { step.trig = 0 };
				steps.add(step);
				delta = delta - step.delta;
			} {
				var short = queue.at(0).delta - delta;
				steps.add(queue.at(0).copy.delta_(delta));
				delta = 0;
				queue.at(0).delta_(short).trig_(0);
			}
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

	fill { |delta| queue.add(JSMNStep(1, delta, value,this.get($:, nil))) }
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
			{ "!@#$%^&*()+-={}[]:\"':;/?<>,|".contains(ch) }
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
		instance.isModifier_("/*@%:?!".contains(value));
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


/* ==== everything below here is deprecated per 2024-07-05 ==== */

Deprecated_Pmini {
	*new { |mini_notation|
		var parser = JSMini(mini_notation);
		
		^Pn(Plazy({	Pseq(parser.cycle, 1) }), inf)
	}
}

// Exposes the main methods that you would want to use.
JSMini {
	var parser, queue;
	
	*new { |mini_notation|
		^super.newCopyArgs(JSMiniParser(mini_notation).parse);
	}

	// return the next step, in the form of an array:
	// [\trig, \delta, \dur, \str, \num]
	step {
		queue = queue ? List.new;
		if(queue.size <= 0) { queue.addAll(parser.next_cycle) };
		^queue.removeAt(0);
	}

	// return the next cycle, in the form of an array:
	// [
	//   [\trig, \delta, \dur, \str, \num],
	//   [\trig, \delta, \dur, \str, \num],
	//   [\trig, \delta, \dur, \str, \num],
	//   ..
	// ]
	cycle {	^parser.next_cycle }

	// log the first n cycles to the post window, for debugging
	log { |cycles=1| parser.log(cycles) }

	// log the tree of nodes to the post window, for debugging
	log_nodes { parser.log_nodes }

	// log the parsed tokens to the post window, for debugging
	log_tokens { parser.log_tokens }
}

// all the classes below this line you should never need to known about
// ====================================================================
// unless you want to know how everything works

// the main parser class.
// returns arrays with 5 elements: \trig, \delta, \dur, \str, \num
//
// "1 [2 3]@2"     : 1(1/3) 2(1/3) 3(1/3)
// "1 [2 3] _"     : 1(1/3) 2(1/6) 3(1/2)
// "1 [2 3] <4 _>" : 1(1/3) 2(1/6) 3(1/6) 4(1/3), 1(1/3) 2(1/6) 3(1/2)
//
JSMiniParser {
	var <>lexer, <>root_token, <>root_node, <>queue;
	
	*new { |str| ^super.newCopyArgs(JSMiniLexer(str)) }

	parse {
		root_token = JSMiniToken("[");
		this.parse_tokens(root_token, nil); // parse up to EOF
		root_node = JSNode.new.parse(root_token);
		root_node.do_repeats;
		root_node.dur_(1.0);
	}

	log { |cycles=1|
		var nodes_logged;
		//this.log_tokens;
		cycles.do { |cycle_number|
			var cycle = this.next_cycle;
			nodes_logged ?? { this.log_nodes; nodes_logged = 1 };
			"cycle %".format(cycle_number).postln;
			cycle.do { |step| step.postln; };
		}
	}

	log_nodes { root_node.log }
	log_tokens { root_token.log }
	
	get { ^root_node.get_steps.collect { |step| step.asArray } }

	// after all mini-notation logic, a cycle comes out, which still
	// may contain "_" steps. these steps should make the step before
	// it sound longer and should make no sound by themselves.
	// this is handled here, just before the cycle is returned.
	next_cycle {
		var cycle, prev_step, index;
		
		queue = queue ? List.new;

		while { queue.size <= 0 } { queue.add(root_node.get_steps) };

		// check for "_" steps within the cycle
		// after this, prev_step will be last non "_" step of the cycle
		(cycle = queue.removeAt(0)).do { |step|
			if(step.str == "_") {
				step.trig = 0;
				if(prev_step.isNil) {
					step.str = "~";
				} {
					prev_step.dur = prev_step.dur + step.dur;
					step.str = prev_step.str;
					step.num = prev_step.num;
				};
			} {
				prev_step = step;
			}
		};
		
		// also check for "_" steps at the start of the next cycle(s)
		
		index = 0; // index of the cycle in the queue that we want to check
        while { index >= 0 } {
			while
			{ queue.size <= index }
			{ queue.add(root_node.get_steps) };

			queue.at(index).do { |step|
				if(index >= 0) {
					if(step.str == "_") {
						step.trig = 0;
						if(prev_step.isNil) {
							step.str = "~";
						} {
							prev_step.dur = prev_step.dur + step.dur;
							step.str = prev_step.str;
							step.num = prev_step.num;
						}
					} {
						index = -1000; // stop after finding a non "_"
					}
				}
			};

			index = index + 1;
		}

		^cycle.collect { |step| step.asArray };
	}

	


	
	parse_tokens { |parent, until|
		var token = lexer.next;
		
		while
		{ token.notNil }
		{
			case
			{ "[<({".contains(token.val) }
			{
				var until = "]>)}".at("[<({".find(token.val));
				parent.add(token);
				this.parse_tokens(token, until.asString);
			}
			{ token.val == until }
			{
				parent.add(token);
				^token; // return up one recursive level
			}
			{
				parent.add(token); // stay on this level
			};

			token = lexer.next;
		}
	}
}

JSStepQueue {
	var <>queue;

	*new { ^super.newCopyArgs(List.new) }
	
	get { |node|
		while { queue.size <= 0 } { queue.addAll(node.more_steps) };
		^queue.removeAt(0);
	}

	get_delta { |node, delta|
		var result=List.new, time=delta;
		while
		{ time > 0.0001 }
		{
			var step = this.get(node);
			if(step.delta > (time + 0.0001)) {
				var d = step.delta -time;
				queue.insert(0, JSStep(0, d, step.str, step.num));
				step.delta = time;
				time = 0;
			} {
				time = time - step.delta;
			};

			result.add(step);
		}

		^result;
	}
}

JSNode {
	var <>children, <>cycle_number, <>queue, <>str, <>num, <>subgroup;
	var <>euclid, <>degrade, <>slow, <>repeat, <>subdivision, <>elongate;
	var <>dur, <>on;

	*new { ^super.newCopyArgs(List.new, -1, JSStepQueue.new) }
	
	add { |node| children.add(node) }

	get_steps { |dur_override|
		^queue.get_delta(this, dur_override ? dur)
	}

	more_steps {
		var result, d;

		cycle_number = cycle_number + 1;
		
		this.do_divide(cycle_number); // fill in dur values in the tree

		case
		{ "[{".contains(str) and: (subgroup == ",") } {
			var step, time, q = PriorityQueue.new;

			result = List.new;
			
			if(str == "[")
			{
				// polyrhythm
				children.do { |child|
					time=0;
					child.get_steps.do { |step|
						q.put(time, step);
						time = time + step.delta;
					};
				};
			} {
				var first_child_dur;

				// polymeter
				children.do { |child|
					time=0;
					first_child_dur = (first_child_dur ? child.dur);
					
					child.get_steps(first_child_dur).do { |step|
						q.put(time, step);
						time = time + step.delta;
					};
				};
			};
			
			time = 0;
			while { q.notEmpty } {
				var next = q.topPriority();
				step = q.pop();
				result.last !? { result.last.delta_(next - time) };
				result.add(step);
				time = next;
			};
			result.last !? { result.last.delta_(1 - time) };
		}

		{ (str == "[") and: (subgroup == "|") } {
			// random
			result = children.choose.get_steps.flatten;
		}

		{ str == "{" } {
			// subdivision
			result = subdivision.collect { |index|
				children.wrapAt(index).get_steps
			} .flatten;
		}

		{ str == "<" } {
			// alternate
			result = children.wrapAt(cycle_number).get_steps.flatten
		}
		
		{ ",|[".contains(str) } {
			result = children.collect { |child|
				child.get_steps
			} .flatten;
		}

		{
			var trig = (degrade ? 1).coin.asInteger; // drop triggers
			if(str == "~") { trig = 0 }; // JST 2024-01-31
			result = [ JSStep(trig, 1, str, num) ]
		};

		// apply slow
		d = dur * ((slow ? "1").asFloat);
		result.do { |step|
			step.dur_(step.dur * d);
			step.delta_(step.delta * d);
		};

		
		/*

			[1 2]         : 1(1/2) 2(1/2)
			[1 2(3,8)]    : 1(1/2) 2(3/16) 2(3/16) 2(2/16)
			[1 2](3,8)    : 1(3/16) 2(3/16) 1(3/16) 2(3/16) 1(2/16) 2(2/16)

			just repeat the array of steps according to your euclid xyz
			on the root node, euclid is not allowed / possible
			so, do it on your children during get_steps

		*/
		if(euclid.notNil) {
			var x=1, y=1, z=0, time;

			// TODO: loop keeps looping when it could already stop!
			time = 0;
			euclid.children.at(0).get_steps.do { |step|
				if(on < (time + step.delta - 0.0001)) {
					x = step.str.asInteger;
					time = -1000;
				};
				time = time + step.delta;
			};
			
			time = 0;
			euclid.children.at(1).get_steps.do { |step|
				if(on < (time + step.delta - 0.0001)) {
					y = step.str.asInteger;
					time = -1000;
				};
				time = time + step.delta;
			};

			if(euclid.children.size > 2) {
				time = 0;
				euclid.children.at(2).get_steps.do { |step|
					if(on < (time + step.delta - 0.0001)) {
						z = step.str.asInteger;
						time = -1000;
					};
					time = time + step.delta;
				};
			};

			result = this.get_euclid_steps(result, x, y, z);
		}

		^result;
	}

	get_euclid_steps { |steps, xval, yval, rval|
		var slots, sum, left_over, spread;

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
		slots = slots.rotate(rval ?? 0);

		// 5: modify the given steps
		sum = slots.sum;

		^slots.collect { |slot|
			steps.deepCopy.collect { |step|
				step.dur = step.dur * slot / sum;
				step.delta = step.delta * slot / sum;
				step;
			}
		} .flatten;
	}

	/* ---- debugging -- */
	
	log { |indent = ""|
		var modstr="";
		
		slow !? { modstr = modstr ++ "/%".format(slow.round(0.01)) };
		repeat !? { modstr = modstr ++ "!%".format(repeat) };
		subdivision !? { modstr = modstr ++ "%%".format("%",subdivision) };
		elongate !? { modstr = modstr ++ "@%".format(elongate) };
		degrade !? { modstr = modstr ++ "?%".format(degrade) };
		subgroup !? { modstr = modstr ++ subgroup };
		
		"% % % %".format(
			indent,
			str.quote,
			(dur ? 0).asFloat.round(0.001),
			modstr
		).postln;
		
		children.do { |child| child.log(indent ++ "--") };
		euclid !? { euclid.log(indent ++ "**") };
	}

	printOn { |stream| stream << "% %".format(str, euclid) }

	/* ----- parsing ---- */
	modify { |what, value|
		case
		{ what == "/" } { slow = (slow ? 1) * (value.asFloat) }
		{ what == "*" } { slow = (slow ? 1) / (value.asFloat) }
		{ what == "@" } { elongate = value.asFloat }
		{ what == "!" } { repeat = value.asInteger }
		{ what == "%" } { subdivision = value.asInteger }
		{ what == "?" } { degrade = value.asFloat }
		{ what == ":" } { num = value.asInteger }
		{};
	}

	maybe_add_new {
		if(children.last.str.notNil) { children.add(JSNode.new) };
	}
	
	parse { |token|
		var index = 0;
		str = token.val;

		while { index < token.children.size } {
			var child_token = token.children.at(index);
			var child_val = child_token.val;
			
			index = index + 1;
			
			if(children.size <= 0) { children.add(JSNode.new) };

			case
			{ "]>)}".contains(child_token.val) } { /* ignore */ }
			{ child_val == " " } { this.maybe_add_new }
			{ "|,".contains(child_val) } {
				subgroup = child_val;
				this.maybe_add_new;
				children.last.str_(child_val);
				children.add(JSNode.new);
			}
			{ "/@*!%:".contains(child_val) } {
				var val = token.children.at(index).val;
				children.last.modify(child_val, val);
				index = index + 1;
			}
			{ "?".contains(child_val) } {
				var val = 0.5; // standard value, next token is optional
				if(index < token.children.size) {
					var nextval = token.children.at(index).val;
					if(nextval.asFloat.asString == nextval) {
						val = nextval.asFloat.clip(0.0, 1.0);
						index = index + 1; // consume the token
					}
				};
				children.last.modify(child_val, val);
			}
			{ child_val == "(" }
			{
				children.last.euclid = JSNode.new.parse(child_token)
			}
			{ children.last.parse(child_token) };
		};

		if(subgroup.notNil) {
			var newchildren = List.new.add(JSNode.new.str_(subgroup));
			children.do { |child|
				case
				{ child.str == subgroup }
				{ newchildren.add(JSNode.new.str_(subgroup)) }
				{ newchildren.last.add(child) }
			};
			children = newchildren;
		}
	}

	do_repeats {
		children.do { |child| child.do_repeats }; // bottum up
		
		// if any of your children needs to be repeated, then do so now
		children = children.collect({ |child|
			var repeat = child.repeat;

			child.repeat = nil;

			case
			{ repeat.isNil } { child }
			{ repeat <= 1 } { child }
			{ repeat.collect { child.deepCopy } };
		}).flatten.asList;

		euclid !? { euclid.do_repeats };
	}

	// establish a dur for all your children
	do_divide { |cycle_number|
		var sum, time;

		sum = children.collect { |child| (child.elongate ? 1) } .sum;
		
		children.do { |child|
			case
			{ str == "<" } { child.dur = (child.elongate ? 1) }
			{ str == "(" } { child.dur = 1 }
			{ str == "[" } {
				case
				{ ",|".contains(child.str) } { child.dur = 1 }
				{ child.dur = (child.elongate ? 1) / sum }
			}
			{ str == "{" } {
				var first_child_count = children.first.children.size;
				
				case
				{ subdivision.notNil }
				{ child.dur = 1 / subdivision }
				{ child.str == "," }
				{
					case
					{ child == children.first }
					// first child gets duration 1
					{ child.dur = 1	}

					// other childs must wrap their children
					// so i give them duration relative to
					// first child, based on the amount of children
					{ child.dur = child.children.size / first_child_count }
				}
				// there must be a subdivision or grouping
				{ "do_divide: unknown {} situation".throw };
			}
			{ child.dur = (child.elongate ? 1) / sum }
		};

		// also give each child a value for "on", which will be used
		// for when calculating xyz euclid values
		time = 0;
		children.do { |child|
			child.on = time;
			time = time + child.dur;
		};
		
		// recurse down the tree
		children.do { |child| child.do_divide(cycle_number) };

		euclid !? { euclid.do_divide(cycle_number) };
	}
}

JSMiniLexer {
	var <>str, <>tokens, <>index;

	*new { |str| ^super.newCopyArgs(str, List.new, 0) }

	next {
		if(tokens.size <= 0) { this.parse_one_token };
		if(tokens.size > 0) { ^JSMiniToken(tokens.removeAt(0)) };
		^nil;
	}

	peek { |offset|
		while { (tokens.size <= offset) and: (index < str.size) } {
			this.parse_one_token
		};
		
		if(tokens.size > offset) { ^JSMiniToken(tokens.at(offset)) };
		
		^nil;
	}
	
	parse_one_token {
		var val = nil;
		
		while
		{ index < str.size }
		{
			var ch = str[index];

			index = index + 1;

			case
			{ ch == $. } {
				if(val.isNil) { ^tokens.add(ch) } { val = val ++ ch };
			}
			{ ch == $_ } {
				if(val.isNil) { ^tokens.add(ch) } { val = val ++ ch };
			}
			{ ("[]{}()<>,%/*!|~@?: ").contains(ch) } {
				val !? { tokens.add(val); val = nil; };
				^tokens.add(ch);
			}
			{ val = val ++ ch };
		};

		val !? { tokens.add(val); val = nil; };
	}
}

JSMiniToken {
	var <>val, <>children;
	
	*new { |val| ^super.newCopyArgs(val.asString, List.new)	}

	add { |token| children.add(token) }

	addAll { |aList| children.addAll(aList) }
	
	log { |indent = ""|
		"% %".format(indent, val.quote).postln;
		children.do { |child| child.log(indent ++ "--") };
	}

	printOn { |stream| stream << "token %".format(val.quote) }
}

JSStep {
	var <>trig, <>dur, <>str, <>num, <>delta;

	*new { |trig, dur, str, num|
		^super.newCopyArgs(trig, dur, str, num, dur)
	}

	asArray {
		^[trig, delta, dur, str, num];
	}
	
	printOn { |stream|
		stream << "step % % % % %".format(
			trig,
			delta.round(0.01),
			dur.round(0.01),
			str.quote,
			num
		);
	}
}
