# Pmini
Compact eventpattern generator for SuperCollider

Inspired by ddwChucklib quark by James Harkins and Tidal.

```Pmini(<sound>, <specs>)```

```Pmini(<specs>)```

sound: name of a synthdef or sample (optional)   
numbers: what notes or samples to play

If you omit an argument, the remaining arguments "shift to the left".

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

# Specs

The ```<specs>``` string supplies multiple specs, separated by capital letters:
<table>
<tr>
<th>capital</th>
<th>description</th>
<th>generated Pbind keys</th>
</tr>
<tr><td>N</td><td>number</td><td>\type \dur \sustain \degree \bufnum</td></tr>
<tr><td>A</td><td>amplitude</td><td>\type \dur \amp</td></tr>
<tr><td>V</td><td>amplitude</td><td>\type \dur \amp</td></tr>
<tr><td>O</td><td>octave</td><td>\type \dur \octave</td></tr>
<tr><td>P</td><td>pan</td><td>\type \dur \pan</td></tr>
</table>

For each key you supply a capital letter, followed by the "spec".   

```"N1234A123P08O5"```

As you can imagine, this could be extended with more capital letters.
The numeric values range from 0 .. z (0 .. 35).

# Dur

In the key-spec's, one character stands for 1 "step" in a bar/cycle.  

So the "N" spec above, divides the bar in 4 equal steps.  
With beatsPerBar = 4, each step will then last 1 beat.  
And so this spec will generate a \dur of 1 for each step.

Each part N, A or P is capable of delivering the \dur key, and so the following rule applies: the \dur key is taken from the _first_ spec that has been supplied but it is _overruled_ by the _last_ spec that has an extra "+" character right after its capital letter.

```"N1234A123P+08"```

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

The syntax for each spec is as follows:

```
0 .. z  integer value 0 .. 35
%       random number 1 .. 9 generated once at interpretation time
?       random number 1 .. 9 generated new for each event
-       plays a rest
~       glides previous step to a new value
space   adds sustain to previous step

*n plays step n times faster within the step duration
/n plays step n times slower within the step duration
!n repeats a step n times
'n adds n * 0.1 to degree, or speeds up sample playback rate by n
,n substract n * 0.1 to degree, or slows down sample playback rate by n

<..>    plays enclosed steps one by one
[..]    plays enclosed steps within this step (faster)
{..}    plays one of the enclosed steps randomly
(..)n   plays enclosed steps together, strum: 0..b neg, c none, d..o pos
```

# Instrument

Depending on playing a sample or synthdef, the \instrument key will be generated.

# Degree / Bufnum

When a rest is to be generated, \degree will be \rest, which will make the event mechanism of SuperCollider generate a rest.

In all other cases, a number is generated, used to specify \degree (when playing synthdef) or a \bufnum (when playing a sample).

# Amplitude

The values from the A and V spec are multiplied, resulting (after some calculation via dbamp) in a value ranging from 0 to 1. Two specs are used, so that you can use A for an accent pattern, and V for the volume 'in the mix'.  
If you omit A and V, Pmini will not generate the \amp key.

# Pan

Mostly a float from -1.0 to 1.0. Here we divide integers 0 to 8 by 4 and subtract 1.0, resulting in -1.0, -0.75, -0.5, -0.25, 0.0, 0.25, 0.5, 0.75 and 1.0. So number 4 will be the center. If you omit P, then Pmini will not generate \pan.

# Can i generate just a pattern, not an eventpattern

You can. If you do not use _any_ capital letter in the "specs" argurment for Pmini, then it assumes that the "specs" argument is only one "spec", and it will generate a Pseq pattern for the supplied spec.

Still, it could then also supply a \dur key, and so then the "sound" argument is used to specify what you want generated:

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

## Installation

Just put ```pmini.sc``` in the Extensions folder of your SuperCollider installation.

