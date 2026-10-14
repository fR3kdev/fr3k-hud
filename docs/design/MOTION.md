# Motion and reduced motion

The redesign uses immediate state transitions for navigation, guide expansion, evidence arrival and execution status. This deliberately has a zero-duration baseline and honours reduced-motion users without relying on Android's animator scale to disable a custom animation. Material press feedback remains platform-managed. No custom animation gates an action or hides a failure.

If future animation is added: press feedback 80–120ms, disclosure 160ms, navigation 200ms, standard ease-out; never animate critical output beyond 200ms. At animator duration scale 0 all changes must be instantaneous. No autoplay walkthrough, flashing warning, infinite status pulse or simulated workflow progress.

Drag/resize maps pointer deltas to clamped window coordinates once per input event. No physics loop, blur pass or per-frame coroutine. Geometry custom accessibility actions allow Move left/right/up/down and Enlarge/Shrink without a drag gesture. Bounds keep controls on the current display.

Existing orb/particle decoration predates this redesign. Its animation must not be confused with service readiness. Verification records whether system animation-scale settings were zero during the reduced-motion pass. A screenshot of a static frame alone does not prove motion behaviour.
