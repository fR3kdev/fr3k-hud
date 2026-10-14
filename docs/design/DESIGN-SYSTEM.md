# fR3k — midnight glass

## Tokens

| Token | Value | Role |
|---|---|---|
| Background | #080B18 | Opaque legibility floor |
| Surface | #151B30 | Glass panel floor |
| Gradient start | #15132D | Violet ambient light |
| Gradient end | #0A1529 | Blue ambient light |
| Border | #343E60 | 1dp structural hairline, never used for body text |
| Main text | #E6ECF5 | Primary copy |
| Secondary text | #B0BCD5 | Explanations, metadata |
| Violet | #C5A0FF | Primary control, heading accents |
| Blue | #9CBAFF | Secondary accent / technical highlight |
| Ready | #28E0B0 | Always paired with READY / LIVE |
| Needs setup | #FFAA33 | Always names the missing requirement |
| Failed | #FF5577 | Always paired with FAILED / REFUSED |
| Running | #9CBAFF | Text says Running; no perpetual pulse |

Glass depth comes from a dark floor, ambient page gradient, rounded border and elevation. Over arbitrary external app content, popup bodies remain opaque; legibility must not depend on the wallpaper. Guide cards use a 72% surface over the controlled dark page, not over external apps. Primary-button text uses Background, never white over pale violet. State contrast calculations are in evidence/contrast.json.

Spacing: 4 / 8 / 12 / 16 / 20 / 24 / 32dp. Radius: 12dp chips, 20dp cards/windows, Material rounded action buttons. Hairline: 1dp. Popup elevation: 12dp. Minimum interactive hit area: 48dp; primary catalogue CTA 56dp. Expandable cards have no fixed text height.

Typography: sans-serif display 32/38 bold; title 22/28 bold; section 14/20 medium; body 14/22 regular; body-large 16/24; technical values system monospace 12/18 or 14/22; labels 12/18. sp sizing follows system scale. Headings have minimal tracking; only the brand eyebrow uses 1.5sp. Larger font scale uses wrapping and scrolling, not font-size suppression.

Iconography: plain labelled symbols (arrow for navigation, circle plus READY/SETUP for state, corner arrow for resize, × plus accessible close label). Icons alone never explain a destructive action. Use Android's actual permission and installer surfaces for consent.

## Examples

Do: `○ SETUP Accessibility bridge` followed by the exact Android setting. Do: show package/version/signer and recorded undo above Run. Do: `Capture cancelled. Nothing was saved.` Do: let a popup move without dismissing it.

Don't: `online` without checking the relevant service. Don't: promise ad removal or third-party patch support. Don't: show a generated image as a screenshot. Don't: consume the whole screen with a transparent overlay. Don't: encode readiness only by green/amber.

## Motion

See MOTION.md. Operational content appears immediately. Window geometry follows the finger directly. No backdrop blur, infinite shimmer, particle dependency or animated evidence delay is introduced by the deck. Existing orb decoration is separately audited; it is not evidence of readiness.

## Release scope update

Current normal UI uses installed-app selection, identity-bound controls/maps, compatible module import, wireless shell and nine production procedures. Fixture walkthroughs are debug instrumentation-only. Obsolete external privilege/patch-manager setup has been removed from Connections. Internal patch-engine attribution remains necessary. Earlier fixture-specific examples in this document describe lab verification, not default release navigation. See CONTINUATION.md for actual completed/pending acceptance and ROUTING-MATRIX.md for current support.
