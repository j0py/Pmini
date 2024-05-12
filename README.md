# Pmini
Tidal Cycles mini-notation for SuperCollider

Pmini is a pseudo pattern that you can use in a Pbind to generate steps using the Tidal Cycles mini-notation.
It is a part of my bigger project called "Tidy", which will also enable you to work with multiple buses (orbits) in the SuperCollider interpreter and to combine function/pattern combinations like in Tidal Cycles.

I start SuperCollider classes with my initials ("JS") so as not to conflict with any other classnames.  
Pmini is an exception though, as patterns must start with a "P".

After attending the ICLC2023 in Utrecht i want to share my stuff on GitHub.  

## Usage example

```
// start SuperCollider and then:

s.boot

Pbind(
  [\trig, \delta, \dur, \str, \num], Pmini("1 2 3 4"),
  \degree, Pfunc({ |e| if(e.trig > 0) { e.str.asInteger } { \rest } }),
).play

```

## JSMini

The first thing i started working on is the mini-notation.

You can find the full specification for this in the Tidal Cycles website: http://tidalcycles.org/docs/reference/mini_notation/ . At this time (08-2023) everything is supported, except the "marking your feet" option where you can use a "." to create groups instead of "[]" brackets.

```
char  description                      test                      supported
----------------------------------------------------------------------------
~     rest                             "1 2 ~ 4"                 Y
[]    grouping                         "1 2 3 [4 4]"             Y
.     shorthand for grouping           "1 . 2 . 3 . 4 4"         -
,     multiple patterns at same time   "1 2 [1,3] 4"             Y
*     play pattern faster              "1 2 3*1.5 4"             Y
/     slow down a pattern              "1 2 3/1.5 4"             Y
|     random choice                    "1 2 [3|4] 4"             Y
<>    alternate patterns               "1 2 <1 3 4> 4"           Y
!     replicate pattern                "1 2!2 3"                 Y
_     elongate                         "1 _ 2 4"                 Y
@     elongate                         "1@2 3 4"                 Y
?     randomly remove events           "1? 2? 3? 4?"             Y
:     selecting samples                "1:1 2:2 3:3 4:4"         Y
()    euclidean sequences              "1 2 3(3,8) 4"            Y
{}    polyrhythmic sequences           "{1 2 1 2, 2 3 4}"        Y
{}%   polymetric sequence              "{1 2 3}%8"               Y
```

For writing the parser, this website inspired me a lot: (https://supunsetunga.medium.com/writing-a-parser-getting-started-44ba70bb6cc9).

JSMini will parse the given mini-notation string and return the cycles/steps to you in the form of arrays that contain numbers and/or strings. A "cycle" is just an array of steps, and a step is an array containing:

```
- "trig"   : if the step should play (value = 1), or just occupy time silently (0)
- "delta"  : the amount of time that should pass before the next step is played (float)
- "dur"    : the duration of the step in cycles (how long does it "sound") (float)
- "string" : the string portion of a note (from the "ss:n" format) (string)
- "number" : the number portion of a note (integer)
```

Example:
```
x = JSMini("1 2 <3 6> 4")

x.cycle
x.step
```
The example above should result in cycles "1 2 3 4" and "1 2 6 4".

JSMini is used by Pmini (and will also be used by JSTidy).

The ```log_nodes``` method will log the internal tree that has been parsed to the post window.  

The ```log_tokens``` method will log what tokens have been parsed from the pattern specification.

The ```log(n)``` method will log the first n cycles to the post window.  

These functions can be used to test if the parser is working as expected, and if not, where it may go wrong.

### Written in SuperCollider

The given pattern string is parsed into a tree of objects, and then the root of that tree is asked for the next cycle (a bunch of steps). The root uses it's children to get their steps and so on. Each child may have different parameters that will make it generate steps differently.  
This way, the nesting, alternating and such has been implemented.

Things can get pretty complicated if you can do things like "1 2 3/5.11 4".  
The third step should play a lot more slow than steps 1 2 and 4. What JSMini does is, it reserves a quarter of the cycle for each step, and if a step wants to play slower, then it may do so within the time that has been reserved for it.  

So if for example step 3 is like "3/2", then in the first quarter cycle, it will trigger at the beginning of that quarter. But it will not fit in that quarter, so the remaining time (not fitting in the quarter) is calculated, and remembered in the step object in the parse-tree. The next time this step get's a chance to play in it's quarter cycle, it will first check if there still is remaining time to wait out. First this remaining time is "consumed", and when that has happened, then the step will trigger a note once again. This may happen at any speed, so "1 2 3/8.4362 4" is perfectly possible.

Playing faster works the same: the quarter cycle is filled with triggered steps, each occupying some amount of time, until it has been filled completely. It's like filling a bucket (quarter of a step) with water (time). When there is water left over after the bucket is full, it is kept for the next bucket.

The "_" character in the pattern will add it's available time to the step before it, which will then last longer. This works within one cycle, but also over to the next cycle.
Consider a pattern like ```"<_ 1> 2 _ 3"```: every other cycle, step "3" should last 1/2 cycle.

Another thing i encountered with alternating steps is, that you cannot use the cycle number to select the alternative. I did that at first using a modulo (%) operation: for 2 alternatives, i used ```cycle % 2```.  
But if you do that, then a pattern like ```"1 2 <3 <4 5>> 6"``` will result in "1 2 3 6", "1 2 5 6", "1 2 3 6" etc, but never "1 2 4 6". This is because "<4 5>" will only be selected when the cycle number is odd, and within this sub-pattern, "5" will always be selected because the cycle number is odd!  
To solve this, each node in the tree counts cycles by itself: each call to the "make more steps" method will increment it. The step uses that internal counter for the modulo operation. And then you will get "1 2 3 6", "1 2 4 6", "1 2 3 6", "1 2 5 6", etc.

An example not using patterns, but just JSMini:

```
Routine({
  // played parts derived from video
  // "Philip Glass Polyrhythms | Minimalist chord trick"
  // https://www.youtube.com/watch?v=gxismSpBtII
  
  var parts = [
    "[a4 e5, [a5 c6 a5 c6 a5 c6]/2]",
    "[g4 e5, [g5 c6 g5 c6 g5 c6]/2]",
    "[f4 f5, [a5 c6 a5 c6 a5 c6]/2]",
    "[e4 e5, [a5 b5 a5 b5 a5 b5]/2]",
    "[e4 d5, [g#5 b5 g#5 b5 g#5 b5]/2]",
    ];
    
  var song = JSMini("0!4 1!4 2!4 3!2 4!2");

  loop {
    song.cycle.do { |part|
      var trig, delta, dur, str, num;
      # trig, delta, dur, str, num = part;
      part = JSMini(parts.wrapAt(str.asInteger));
      part.cycle.do { |step|
        var freq, synth;
        # trig, delta, dur, str, num = step;
	
	// notemidi is defined in one of the SC3Plugins
        freq = str.notemidi.midicps;

        Routine({
          s.bind { synth = Synth(\default, [\freq, freq]) };
          dur.wait;
          s.bind { synth.set(\gate, 0) };
        }).play;

        delta.wait;
      }
    }
  }
}).play
```


Another example demonstrating all supported mininotation features:

```
s.boot

TempoClock.default.tempo_(0.5)

Routine({
	var patterns = [
		"1 2 ~ 4",
		"1 2 3 [4 4]",
		//"1 . 2 . 3 . 4",     not suported by Pmini (yet)
		"1 2 [1,3] 4",
		"1 2 3*3 4",
		"1 2 3/3 4",
		"1 2 [3|4] 4",
		"1 2 <1 3 4> 4",
		"1 2!2 3",
		"1 _ 3 4",
		"1@2 3 4",
		"1? 2? 3? 4?",
		"1:1 2:2 3:3 4:4",     // use sample number for panning to test it
		"1 2 3(3,8) 4",
		"{1 2 1 2, 2 3 4}",
		"{1 2 3}%8",
	];
	var x;
	patterns.do { |pattern|
		pattern.quote.postln;
		x = Pbindef(\pmini,
			[\trig, \delta, \dur, \str, \num], Pmini(pattern),
			\degree, Pfunc({ |e|
				if(e.trig <= 0) { \rest } { e.str.asInteger }
			}),
			\pan, Pfunc({ |e|
				e.num !? { e.num.linlin(1,4,-1,1) } ?? { 0 }
			}),
			\legato, 0.2
		).play;
		4.wait;
	};
	x.stop;
}).play

```

Uses the default synth (with shorter notes so you can hear the pattern).
