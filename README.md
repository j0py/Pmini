# Pmini
Tidal Cycles syntax for SuperCollider

Pmini is a pseudo pattern that you can use in a Pbind to generate steps using the Tidal Cycles mininotation. It is a part of my bigger project called "Tidy", whick will also enable you to work with multiple buses (orbits) in the SuperCollider interpreter.

I start SuperCollider classes with my initials ("JS") so as not to conflict with any other classnames.  
Pmini is an exception though, as patterns must start with a "P".

After attendeding the ICLC2023 in Utrecht i want to share this on GitHub.  

## Usage example

```
// start SuperCollider and then:

s.boot

Pbind(
  [\trig, \delta, \dur, \str, \num], Pmini("1 2 3 4"),
	\degree, Pfunc({ |e| if(e.trig > 0) { e.str.asInteger } { \rest } }),
)

```

I made a video to explain a bit more about how you can use the Pmini class:  
https://www.youtube.com/watch?v=dsB9Ue1o6Eg

## JSMini

I added this class so that you can use mininotation to generate steps without using any Pattern classes.

```
parser = JSMini("1 2 3 4");

parser.next();  // returns [1, 0.25, 0.25, 1, nil]
parser.next();  // returns [1, 0.25, 0.25, 2, nil]
...
```

## the JSMiniParser

The first thing i started working on is the mini-notation.

You can find the full specification for this in the TidalCycles website: http://tidalcycles.org/docs/reference/mini_notation/ . At this time (08-2023) everything is supported, except the "marking your feet" option where you can use a "." to create groups instead of "[]" brackets.

For writing the parser, this website inspired me a lot: (https://supunsetunga.medium.com/writing-a-parser-getting-started-44ba70bb6cc9).

Usage:
```
x = JSMiniParser("1 2 <3 6> 4")

x.next_cycle
x.next_cycle
```
Each call to "next_cycle" will return an array of steps, where each step is an array by itself, holding these values: trig, delta, dur, str, num.

```
- "trig"   : if the step should play, or just occupy time silently
- "delta"  : the amount of time that should pass before the next step is played
- "dur"    : the duration of the step in cycles (how long does it "sound")
- "string" : the string portion of a note (from the "ss:n" format)
- "number" : the number portion of a note
```

The example above should result in cycles "1 2 3 4" and "1 2 6 4".

JSMiniParser is used in Pmini (and will also be used in Tidy).

The ```log_nodes``` method will log the internal tree that has been parsed to the post window.  

The ```log_tokens``` method will log what tokens have been parsed from the pattern specification.

The ```log(n)``` method will log the first n cycles to the post window.  

These functions can be used to test if the parser is working as expected, and if not, where it may go wrong.

### Inner working

The given pattern string is parsed to a tree of objects, and then the root of that tree is asked for the next cycle (a bunch of steps). The root uses it's children to get their steps and so on. Each child may have different parameters that will make it generate steps differently.  
This way, the nesting, alternating and such has been implemented.

Things can get pretty complicated if you can do things like "1 2 3/5.11 4".  
The third step should play a lot more slow than steps 1 2 and 4. What JSMini does is, it reserves a quarter of the cycle for each step, and if a step wants to play slower, then it may do so within the time that has been reserved for it.  

So if for example step 3 is like "3/2", then in the first quarter cycle, it will trigger at the beginning of that quarter. But it will not fit in that quarter, so the remaining time (not fitting in the quarter) is calculated, and remembered in the step object in the parse-tree. The next time this step get's a chance to play in a quarter cycle, it will first check if there still is remaining time to wait out. So first this waiting time is "consumed", and when that has happened, then the step will trigger once again. This may happen at any speed, so "1 2 3/8.4362 4" is perfectly possible.

Playing faster works the same: the quarter cycle is filled with triggered steps until it has been filled completely. It's like filling a bucket (quarter of a step) with water (time). When there is water left over after the bucket is full, it is kept for the next bucket.

The "_" results in a Space node object in the tree. The duration of this step is added to the duration of the previous step, which will then last longer. This works within one cycle, but also over to the next cycle.
Consider a pattern like ```"<_ 1> 2 _ 3"```: every other cycle, step "3" lasts 1/2 step.

Another thing i encountered with the alternating steps is, that you cannot use the cycle number to select the alternative.I did that at first using a modulo (%) operation: for 2 alternatives, use ```cycle % 2```.  
If you do that, then a pattern like ```"1 2 <3 <4 5>> 6"``` will result in "1 2 3 6", "1 2 5 6", "1 2 3 6" etc, but never "1 2 4 6". This because "<4 5>" will only be selected when the cycle number is odd, and within this step, "5" will thus always be selected.  
Each node in the tree counts cycles by itself: each call to the "make more steps" method will increment it. The step uses that for the modulo operation. And then you will get "1 2 3 6", "1 2 4 6", "1 2 3 6", "1 2 5 6", etc.

