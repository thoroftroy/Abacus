# Abacus changelog

## v0.5.15

- Stays upright on phones because the keypad is not usable sideways. Turn on Rotate with the phone in Settings to change that
- Text size and accent colour chosen in Control Room now apply here

## v0.5.14

- New settings for digits shown in answers, key tick, answer text size and how many calculations are remembered

## v0.5.13

- Loading screens now say what they are doing instead of showing a bare spinner
- Wording tidied so no sentence needs a dash

## v0.5.12

- Empty converter results show dots while there is nothing to convert yet

## v0.5.11

- The result now stays on one line and shrinks to fit, so long fractions fit inside the display instead of getting clipped. Header buttons are slimmer to give the number room

## v0.5.10

- New fraction toggle (1/3) in the display: switch results between exact fractions (“1/3 ≈ 0.333”) and decimals only, also in Settings

## v0.5.9

- Calculator now shows fractions: results like 1/3 display as “1/3 ≈ 0.333333333333” instead of decimal only
- Fixed the RAD/DEG (and Basic/Scientific) toggle not responding. Now it is a proper button with a full touch target

## v0.5.8

- Lighter on the battery: in the Super look the drifting sparkles settle after a few seconds without a touch (the next tap wakes them), so a screen you're reading no longer redraws 25 times a second

## v0.5.7

- Changing the look in Control Room now restyles this app's widgets straight away, with no refresh or restart

## v0.5.6

- Widgets: choose the title colour in each widget's settings (automatic, white, dark, Aqua blue, Super pink or gold), on top of the background looks

## v0.5.5

- Widgets can now match your look: new Bliss blue and Super neon backgrounds, and “Match my Wonderdesk look” follows whatever Control Room is set to

## v0.5.4

- New Tools tab: programmer number bases (hex, octal, binary, bitwise AND/OR/XOR and shifts), days between dates and date maths, tip and bill splitting, discounts with tax, and loan or mortgage repayments. Tap any result to copy it
- Fixes from a full round of stress testing on every screen

## v0.5.3

- Tap the tab you're already on to glide back to the top of its list

## v0.5.2

- Keys give a light tap you can feel
- Smoother everywhere: new screens, tabs and Settings ease in instead of snapping, and buttons, tabs, chips and switches give a light tap you can feel (following your phone's touch-feedback setting)
- Android's predictive back: the back gesture shows a live preview of where you're going
- Fixed a graph crash when the graph area was very short
- Super look: tap sparkles now match Bliss Player: soft glowing sparks with trails, drifting rainbow colours and a faint aurora, instead of big solid circles

## v0.5.1

- Much faster and smoother: the app is now built optimised (the way Play Store apps are), so screens open quicker, scrolling is fluid and the APK is about a quarter of the size
- Screens and lists no longer decode pictures on the main thread, and skip redrawing parts that didn't change

## v0.5.0

- Polish and stability fixes from the suite-wide test pass
- Works properly in landscape: tabs move to a rail at the side so the content keeps its height, and screens that ran out of room now scroll
- If the app ever crashes it says so next time it opens and offers to send a report (only the error, app version and phone model, with file names and links removed). Turn on automatic sending in Control Room › Wonderdesk
- Lists can no longer crash when two items share a name

## v0.4.0

- Graphing rebuilt to rival Desmos: as many expressions as you like, y = and x = curves, polar r = θ, parametric curves, points, implicit equations like x² + y² = 16, shaded inequalities and sliders
- Roots, turning points and intersections are marked; tap a curve to trace it
- Very large and small conversions read like 6.2 × 10⁻⁷ instead of 6.2×10^-7
- New Calculator widget that works right on the home screen
- If the app ever crashes or freezes, a report goes to Control Room so it can be shared and fixed
- App icon art is larger and fills its tile without being cut off

## v0.3.1

- Type or paste anything into the new Solve tab: plain maths, symbols copied from a web page, LaTeX, or phrases like “derivative of x² sin x” and “15% of 80”
- Abacus works out what to do on its own (calculate, solve, graph, expand, factor, differentiate or integrate), or pick one yourself
- Show work: step-by-step working for arithmetic, equations, derivatives and integrals, plus a short explanation of how the method works
- y = … and f(x) = … go straight to the graph; systems of lines are drawn together
- A hardware or Bluetooth keyboard types straight into the calculator, and Paste works on both tabs
- Graphs pan and zoom smoothly: each function is compiled once instead of being re-read for every point
- Solving runs in the background, so typing never lags
- App icon redrawn so nothing is cut off: round icons fill the space, the rest sit on an Aqua tile
- Smoother and lighter on the battery: the window background no longer animates, tap effects sleep until you tap, and nothing ticks while the app is in the background
- Opening the app no longer waits on Control Room to read the suite look

## v0.3.0

- Full scientific calculator with backspace, live answers and history
- Algebra: exact linear and quadratic roots, systems, factoring, derivatives and integrals with steps
- Graph up to three functions with pan, zoom and trace
- Unit converter with 12 categories
