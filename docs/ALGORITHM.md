# How the sleep tracking works

Everything here runs in `core/`, a plain Kotlin library with no Android dependencies. Both apps
run the same code on the same input, which is why the phone's hypnogram and the watch's alarm
decision can never disagree.

## The pipeline

```
raw sensors ──▶ epoch features ──▶ sleep/wake ──▶ stage model ──▶ cycles ──▶ wake decision
   20 Hz            every 30 s      Cole-Kripke     4-state HMM    NREM-REM    scored window
```

Every stage is recomputed from the whole night on every new epoch. That sounds wasteful and
isn't: a ten-hour night is 1200 epochs, and the full pass is a few hundred microseconds once
every thirty seconds. It buys something important — the personal baselines that activity and
heart rate are measured against keep sharpening as the night accumulates, so re-running the
night lets a 3 a.m. reading correct a midnight one. Scoring each epoch once, with whatever
baseline happened to exist at the time, bakes the first half hour's guesswork in permanently,
and the first half hour is exactly when the baseline knows least.

---

## 1. From raw samples to epoch features

`EpochFeatureBuilder` turns a stream of accelerometer and heart-rate samples into one feature
vector per 30-second epoch. Thirty seconds is the polysomnography standard.

**Movement.** The acceleration magnitude is band-passed to roughly 0.25–3 Hz, the band human
movement lives in. Gravity is below it and sensor noise is above it, so a perfectly still wrist
scores near zero no matter which way it is pointing. Two cascaded exponential filters form the
pass band; the implementation is allocation-free per sample so it can run for ten hours on a
watch.

From the band-passed signal, per epoch:

| feature | what it is | why |
|---|---|---|
| `activityCount` | integrated rectified signal (milli-g seconds) | how *hard* the wrist moved |
| `movingSeconds` | seconds above 0.02 g | how *long* it kept moving — the real sleep/wake discriminator |
| `zeroCrossings` | band crossings past a dead-band | movement frequency |
| `peakAcceleration` | largest excursion | catches brief jerks |
| `wristAngle` | tilt of the smoothed gravity vector | posture |
| `wristAngleChange` | change since the previous epoch | rolling over |

**Heart rate.** Mean, min, max, spread, and an RMSSD-style variability proxy. The Galaxy Watch 4
exposes averaged bpm to third-party apps rather than raw R-R intervals, so each bpm reading is
converted back to an inter-beat interval and the RMS of successive differences is taken. It is a
proxy — lower resolution than real HRV, tracking the same autonomic swings, which is all the
stage model needs it for.

**Timestamps.** Batched sensor events arrive long after the samples in them were taken and are
stamped against elapsed-realtime, not wall clock. Each sample is converted back to the instant
it was actually recorded; using the delivery time would smear a whole batch into one epoch.

## 2. Personal baselines

`NightBaseline` learns what "still", "low heart rate" and "calm" mean *for this sleeper on this
watch*, from percentiles of the night so far:

- **Activity floor** — the 5th percentile, i.e. a motionless wrist, i.e. this device's noise
  floor. Subtracting it is what makes everything downstream device-independent.
- **Activity scale** — the 75th percentile above the floor: one ordinary light-sleep fidget.
- **Heart-rate floor and ceiling** — the 5th and 92nd percentiles. Heart rate is then expressed
  on the sleeper's own scale, 0 at their nocturnal minimum and 1 at quiet wakefulness, which is
  why the same stage model works for an athlete resting at 42 and someone sitting at 70.

## 3. Sleep versus wake

`SleepWakeScorer` implements **Cole-Kripke** (Cole et al., *Sleep* 1992): a weighted window of
one-minute activity counts, sleep when the weighted sum falls below 1.

```
D = 0.001 × (106·A₋₄ + 54·A₋₃ + 58·A₋₂ + 76·A₋₁ + 230·A₀ + 74·A₊₁ + 67·A₊₂)
```

Cole-Kripke expects ActiGraph counts, where the sleep/wake boundary sits near four counts per
minute. This app's counts come from two physical quantities — intensity above the noise floor,
and seconds spent moving — weighted equally, because *duration* is what really separates being
awake from a sleeping fidget. A sleeper twitches for a fraction of a second; someone awake moves
for seconds at a time.

Both units are absolute rather than percentiles of the night. This matters: a percentile-based
wake threshold on a night that is ninety per cent sleep would declare the quietest movements of
light sleep to be wakefulness by construction. That bug cost about twenty points of accuracy
before it was found.

On top sit **Webster's rescoring rules**, which undo the algorithm's habit of calling the first
few minutes after a long awake stretch "sleep" — you do not drop straight into sleep after
fifteen minutes of tossing — and kill implausibly short sleep islands.

Sleep onset is the first minute of the first ten unbroken sleep minutes: the standard persistent
sleep onset criterion.

## 4. Sleep stages

`SleepStageClassifier` is a four-state hidden Markov model solved with Viterbi over the whole
night. Running Viterbi rather than classifying epochs independently is what stops the output
flickering between deep and REM every thirty seconds, and what lets a later epoch correct an
earlier one.

**What separates the stages:**

| stage | movement | heart rate | HR variability | posture changes |
|---|---|---|---|---|
| Awake | high, sustained | high | high | frequent |
| Light | small, brief | mid | mid | occasional |
| Deep | almost none | at the nightly floor | low | almost none |
| REM | none (atonia) | raised, irregular | high | almost none |

Deep and REM look identical to an accelerometer — both are motionless — so the cardiac features
do all the work of telling them apart. This is the single most important thing to understand
about consumer sleep staging, and it is why the app degrades the way it does when the heart-rate
sensor goes silent (see below).

Movement is enormously skewed, three orders of magnitude between a still wrist and a thrashing
one, so the model sees it in log space where a Gaussian can actually describe it.

**Transitions** encode the sequence rules of sleep: you reach REM through light sleep and
essentially never straight from deep sleep; deep sleep is sticky; every stage mostly persists
from one epoch to the next.

**Two priors make the model cycle-aware:**

- Slow-wave pressure decays exponentially through the night, so deep sleep is expected early and
  becomes rare after about five hours.
- REM propensity is the mirror image — suppressed during the first REM-latency minutes, rising
  all night, and peaking *late within each cycle*. That last part is fed by the previous pass's
  cycle detection, which is what closes the loop between staging and cycles.

A base-rate prior (roughly 10% awake, 22% REM, 50% light, 18% deep across a night) keeps the
tightly-peaked deep and REM templates from winning every quiet epoch on density alone.

## 5. Cycle boundaries

`CycleTracker` finds where cycles actually end, following the classical Feinberg & Floyd
definition: a cycle is a descent into NREM followed by a REM period, and it ends when that REM
period ends.

A cycle closes when any of these happens:

1. **A REM period of at least five minutes ends** — no REM for five minutes afterwards. The
   textbook boundary. The night's *first* REM period is allowed to be as short as three minutes,
   because it routinely is, and holding it to five merges the first two cycles into one.
2. **A long awakening** — ten minutes awake, well into a cycle. Long awakenings cluster at cycle
   ends.
3. **The cycle overran** — past 130 minutes with no scorable REM. The boundary goes at the
   lightest point of the recent ascent out of NREM, where the REM period should have been.

Only boundaries of the first kind count as *measurements*. A cycle closed because it overran
tells you where the night was cut, not how long this sleeper's cycles run, and folding those in
would drag every later prediction towards the cut-off length.

**Learning the cycle length.** Measured cycles are corrected for the known shape of a night —
early cycles run short because they are slow-wave dominated, later ones run long because their
REM periods lengthen — then averaged with later cycles weighted more heavily, since they are the
better guide to the cycles still to come. The result is clamped to 65–120 minutes and carried
across nights in the sleeper's profile.

**Projecting the target.** The estimate is anchored on the last measured boundary and predicts
forward from there. With no measured boundary at all it degrades cleanly to dead reckoning from
sleep onset using the profile, which is what a conventional sleep-cycle alarm does for the whole
night.

## 6. Choosing the moment

`SmartWakeEngine` scores every epoch inside a window around the target on how pleasant waking
now would be, against a bar that **starts high and falls to zero at the deadline**.

The score starts from the stage — awake 1.00, light 0.82, REM 0.40, deep 0.05 — and is adjusted:

- **+** ascending out of deeper sleep over the last few minutes
- **+** a REM period just ended (the true cycle boundary, and the best moment of the night)
- **+** a natural micro-arousal: movement while already in light sleep
- **+** the requested number of cycles has genuinely completed
- **−** currently in a REM period less than eight minutes old — do not cut it short
- **−** descending into deeper sleep
- **−** the stage estimate has low confidence

The bar eases *in*: it holds near 0.8 through most of the window and only concedes as the
deadline closes. A linear or ease-out bar drops so fast that the alarm settles for the first
mediocre moment and wakes you needlessly early — an early version did exactly that.

At the deadline the bar is zero and the alarm fires regardless of stage. Being woken slightly
groggy beats missing your morning.

## Validation

The engine has no real PSG data behind it, so it is tested against **synthetic nights built from
a known ground-truth hypnogram**. `SyntheticNight` generates the accelerometer and heart-rate
streams a wrist would actually produce in each stage — movement as discrete bursts at
physiological rates, heart rate as a lagged first-order response to a stage target — and pushes
them through the *real* `EpochFeatureBuilder` from raw 20 Hz samples. The signal models are
deliberately not a copy of the classifier's assumptions; the classifier sees only the same
band-passed counts and bpm averages a real watch would hand it.

On a standard six-cycle night:

```
truth\pred     AWAKE     REM   LIGHT    DEEP
AWAKE             29       0       0       0
REM                0     251       3       0
LIGHT              4      39     699      13
DEEP               0       0       8     154
agreement = 94.4%
```

Detected cycle ends: 95, 183, 273, 365, 466, 558 minutes.
True cycle ends: 95, 177, 272, 364, 460, 555 minutes.

The test suite also checks that a 75-minute-cycle sleeper and a 108-minute-cycle sleeper are
told apart, that fewer requested cycles means an earlier alarm, that the alarm never fires out
of deep sleep on a normal night, that the deadline is honoured for a sleeper who never stirs,
and that a night's stage minutes add up.

**What this number does not mean.** Synthetic nights are cleaner than real ones: no watch
slipping down the wrist, no partner's movement, no arrhythmia, no cat. A real-world figure would
be lower, and the honest published range for wrist-based four-stage classification against PSG
is roughly 60–80%. Treat 94% as evidence the implementation does what it is designed to do, not
as a clinical accuracy claim.

## When the heart-rate sensor goes quiet

Deep and REM are told apart almost entirely by the heart. With no cardiac reading there is no
evidence for either, so the model stops claiming them rather than inventing them: it falls back
to the light/awake distinction that movement alone genuinely supports, reports lower confidence,
and the app says so on screen. Cycle detection cannot work without REM, so the alarm target
falls back to dead reckoning from the profile. Sleep and wake tracking — which is pure
actigraphy — carries on unaffected.

## References

- Cole RJ, Kripke DF, et al. *Automatic sleep/wake identification from wrist activity.* Sleep,
  1992.
- Webster JB, Kripke DF, et al. *An activity-based sleep monitor system for ambulatory use.*
  Sleep, 1982.
- Feinberg I, Floyd TC. *Systematic trends across the night in human sleep cycles.*
  Psychophysiology, 1979.
- van Hees VT, et al. *Estimating sleep parameters using an accelerometer without sleep diary.*
  Scientific Reports, 2018.
