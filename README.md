# Pmini
Compact eventpattern generator for SuperCollider

Inspirated by ddwChucklib quark by James Harkins and Tidal.

```Pmini(<bpb>, <sound>, <numbers>)```

```Pmini(<sound>, <numbers>)```

```Pmini(<numbers>)```

bpb: beats per bar (optional, default: 4)   
sound: name of a synthdef or sample (optional)   
numbers: what notes or samples to play

If you omit an argument, the remaining arguments "shift to the left".

# Beats per bar (bpb)

You can supply a number for the beats-per-bar that Pmini will use. Default 4.

# Sound

You can supply the sound to play, as a string. Optional. When omitted, Pmini assumes you want to play the \default synth.

If you supply a "sound", then Pmini will check if there is a sample by that name. If there is, then Pmini will generate a Pbind with a \bufnum key. This Pbind will need a sample playing synthdef name (for the \instrument key), and you can specify that name via the class variable Pmini.playbuf_synthdef, which defaults to "playbuf".

Samples are expected in ```Library.at(\samples)```.

If you have a "samples" folder in the same folder as your current .scd file, then you could do this:

```
s.waitForBoot({
	("samples".resolveRelative +/+ "*").pathMatch.do({ |bank|
		Library.put(
			\samples,
			bank.basename.withoutTrailingSlash.asSymbol,
			(bank +/+ "*.*").pathMatch.collect({ |file|
				Buffer.read(s, file)
			})
		);
	});
	s.sync;
});
```

This would read ALL files (.wav, .aiff, etc) as samples into the library.

Each sub-folder of the "samples" folder will appear in the Library under the \samples key, and it will hold an Array with audio Buffers read from the files that were found in the sub-folder.

If a sample by the name of the given "sound" is not found, then Pmini assumes there is a Synthdef by that name, and will generate a Pbind playing that synthdef.

# Numbers

The ```<numbers>``` can supply 6 Pbind-keys (at the moment):
- \dur
- \instrument
- \degree or \bufnum (N)
- \amp (A)
- \pan (P)
- \octave (O)

For each key you supply a capital letter, followed by the "spec".   

```"N 1234 A 123 P 08 O 5"```

As you can imagine, this could be extended with more capital letters, as long as a single digit ranging from 0 to 9 is enough to specify the value for the Pbind key.

# Dur

In the key-spec's, one character stands for 1 "step" in a bar.

So the "N" spec above, divides the bar in 4 equal steps.  
With beatsperbar = 4, each step will then last 1 beat.  
And so this spec will generate a \dur of 1 for each step.

Each part N, A or P is capable of delivering the \dur key, and so the following rule applies: the \dur key is taken from the _first_ spec that has been supplied but it is _overruled_ by the _last_ spec that has an extra "+" character right after its capital letter.

```"N 1234 A 123 P+ 08"```

In this example, the P part delivers \dur (which will be 2, because the P part divides the bar into 2 steps). The numbers from the other specs will flow through these durations:

```
N A P
1 1 0
2 2 8
3 3 0
4 1 8
1 2 0
..etc
```

A character in a spec may be:

0123456789 : a bufnum or degree (when playing a sample or synthdef)  
x : a rest (the sound of the note before x will stop)  
a space : the duration of the space will be added to the sustain of the previous note, and so the previous note continues to play during this "space".  

There are more different characters to be found in the specs, which can modify the preceding, or following, "real" number. These will be explained further below.

# Instrument

Depending on playing a sample or synthdef, the \instrument key will be generated.

# Degree / Bufnum

When a rest is to be generated, \degree will be \rest, which will make the event mechanism of SuperCollider generate a rest.

In all other cases, a number is generated, used to specify \degree (when playing synthdef) or a \bufnum (when playing a sample).

# Amplitude

Mostly a float between 0 and 1. Here we divide integers 0 to 8 by 8, resulting in floats 0.0, 0.125, 0.25, 0.375, 0.5, .., 1.0, which gives us enough amplitude options. If you omit A, then Pmini will not generate \amp.

This could be improved by mapping exponentially instead of linear, and still have 0 be really 0.  
Maybe ```amp = max(0, digit.linexp(0, 8, 0.001, 1.0) - 0.002)```

# Pan

Mostly a float from -1.0 to 1.0. Here we divide integers 0 to 8 by 4 and subtract 1.0, resulting in -1.0, -0.75, -0.5, -0.25, 0.0, 0.25, 0.5, 0.75 and 1.0. So number 4 will be the center. If you omit P, then Pmini will not generate \pan.

# Can i generate just a pattern, not an eventpattern

You can. If you do not use _any_ capital letter in the "numbers" argurment for Pmini, then it assumes that the "numbers" argument is only one "spec", and it will generate a Pseq pattern for the supplied numbers.

Still, it could then also supply a \dur key, and so the "sound" argument is used, in this case, to specify what you want generated:

"dd" : generate ```[\dur, \degree (or \bufnum)]``` for each event  
"d" : generate \dur for each event  
"" : generate \degree (or \bufnum) for each event  

So you could:
```
Pbind(
  \instrument, \default,
  [\dur, \degree], Pmini("dd", "1234")
  );
```

Or play a triplet feel:

```
Pbind(
  \instrument, \playbuf,
  \dur, Pmini("d", "123"),
  \bufnum, Pseq((10..20),inf)
  );
```

# Modifiers

Inspired by Tidal and ddwChucklib, some modifiers have been added to get more options in the specs.

### Nesting, alternating

Nesting (```"12[33]4"```) puts one or more steps in the duration space of one step. Samples/notes are thus played faster. Note "3" is doubled here.

Alternating (```"12<78>4"```) will alternate the steps between the brackets. The first step is played during the first cycle, and during the next cycle, the second step will be played (if any) and so on. Plays 1274, 1284, 1274, .. etc.

You may nest these things as deep as you want.

### Speed

```"123*24"``` plays note "3" 2 times faster during it's duration.   
```"123/24"``` plays note "3" 2 times slower during it's duration.  

```"123*3/24"``` plays note "3" 1.5 times faster during it's duration.

This is a bit tricky: during the step, 1.5 notes "3" are played.   
That means, in cycle 0, a "3" is played at the beginning of the step, and another is played at 2/3 into the step.
In cycle 1, the step will start with silence, and on 1/3 into the step, a "3" will be played. This last "3" will exactly fill up the step. Cycle 2 will be the same as cycle 0, cycle 3 the same as cycle 1, etc.   

After the ```*``` and ```/``` exactly 1 digit (2..9) is expected.

You may also speed up nested groups of course:

```"12[345]*5/7|4"```

### Up/Down

```"123'34"``` The "'" (up) character will play a sample faster (generates \rate key for the playbuf synthdef). And for synthdef playing will play 3 semitones up (by adding 3 * 0.1 to the \degree key in the Pbind).

```"123,34"``` The "," (down) character goes the opposite way.

```"123~4"``` When playing synthdef, step 3 pitch glides to step 4 pitch.
This is done by adding the \dur of step 4 to the \sustain of step 3 and generate an event with type \set in step 4, supplying the new degree to glide to.

The (gated) synthdef needs to support this by having a Lag or VarLag on the frequency control. The duration for the Lag is the \sustain key. The event with type \set in step 4 will also deliver a \sustain key with the same value as its \dur.

```
SynthDef(\sin, { |out=0, freq=440, gate=1, sustain=1, attack=0.01, amp=0.1, pan=0, rel=1|
	var env, sig;
	sig = SinOsc.ar(VarLag.kr(freq, sustain, -4));
	env = Env.asr(attack, 1, rel).kr(2, gate);
	sig = sig * env;
	Out.ar(out, Pan2.ar(sig, pan, 0.2 * amp));
}).add;
```

### Repeat

```!``` Repeats the previous step. Expects 1 digit (2..9) after the ! character.

```"1!4"``` is the same as ```"1111"```.

### Randomness

```%``` a random digit 0..9, use everywhere, evaluated once during parsing

```?``` a random digit 0..9, use everywhere, evaluated just in time all the time

## Installation

Just put ```pmini.sc``` in the Extensions folder of your SuperCollider installation.

## Plans

```"(123)"``` could play 3 notes simultaneously (a chord) + modifier for strum.

setup a drumkit (useable characters: ```#^-_=:;,.\```):

```Pmini.kit = [$_, "bd", 1, $=, "sn", 2, $^, "hh", 3,.. etc]```

So, ```_``` will be sample number 1 from the "bd" samples, etc.

and then in a "K" part, you specify the drums using your own definition:

Pmini("K _ <=[^^]>(_^) A 2") if "K" is used, then sound arg is ignored

Could be fun..


