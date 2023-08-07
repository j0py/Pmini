/*
Takes a mini notation string as input.
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
	*new { |mini_notation|
		var parser = JSMiniParser(mini_notation).parse;
		
		^Pn(Plazy({	Pseq(parser.next_cycle, 1) }), inf)
	}
}

// this one you can use in a Routine without using Patterns at all
JSMini {
    var parser, queue;

    *new { |mini_notation|
        ^super.newCopyArgs(JSMiniParser(mini_notation).parse);
    }

    next {
        queue = queue ? List.new;
        if(queue.size <= 0) { queue.addAll(parser.next_cycle) };
        ^queue.removeAt(0);
    }
}

// returns arrays with 5 elements: \trig, \delta, \dur, \str, \num
//
// "1 [2 3]@2"     : 1(1/3) 2(1/3) 3(1/3)
// "1 [2 3] _"     : 1(1/3) 2(1/6) 3(1/2)
// "1 [2 3] <4 _>" : 1(1/3) 2(1/6) 3(1/6) 4(1/3), 1(1/3) 2(1/6) 3(1/2)
//
// stack/cat: [xxxx, yyy, zzz] parallel or <xxxx, yyy, zzz> in series
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

	// after all mininotation logic, a cycle comes out, which still
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

		{ result = [ JSStep(1, 1, str, num) ] };

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
			{ ("[]{}()<>,%/*!|_~@?: ").contains(ch) } {
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
