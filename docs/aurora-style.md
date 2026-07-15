# Aurora — visual language (apply to every surface)

Dark-first, ChatGPT-voice idiom. A living light orb is the signature; content sits on frosted
panels over a deep radial background. The accent always follows the selected palette's colorPrimary.

## Colors (already defined in res/values/colors.xml)
- Background: `@drawable/aurora_bg_radial` (deep radial #0E1417 → #05080A). Root of every screen.
- Ink: `@color/aurora_ink` (#ECF3F1) primary text; `@color/aurora_ink_dim` (#8A9A96) secondary/hints.
- Panels/pills/buttons: `@drawable/aurora_panel_bg` (26dp), `@drawable/aurora_pill_bg` (22dp),
  `@drawable/aurora_circle_btn` (oval ripple). All are translucent white (#14FFFFFF) + hairline stroke.
- Accent: resolve `?attr/colorPrimary` at runtime (palette-driven). Use for the orb, waveform,
  active chips, primary buttons, badges.

## Type
- Display / titles / brand: `@font/space_grotesk` with `android:textFontWeight="600"` or `700`.
- Data / time / model chips / mono readouts: `@font/space_mono_regular`.
- Body / transcript / long Cyrillic text: system default (Space Grotesk is Latin-only; do NOT
  force it on Russian content — it falls back per-glyph and looks inconsistent).

## Components
- Icon buttons: 40dp, `@drawable/aurora_circle_btn`, `app:tint="@color/aurora_ink"`.
- Toolbars: title in Space Grotesk 700, back arrow as a circle button, dark bg (no elevation).
- Spinners on dark: add `android:theme="@style/ThemeOverlay.Aurora.OnDark"` so text is light.
- Cards/rows: `@drawable/aurora_panel_bg`, 16–18dp padding, `aurora_ink` / `aurora_ink_dim` text.
- Primary action button: filled with `?attr/colorPrimary`. Destructive: `?attr/colorError`.
- The orb (`com.whispertflite.ui.AuroraOrbView`) is the record/listen affordance wherever recording
  happens (main, dialog, IME): set its colors from colorPrimary/colorPrimaryContainer, feed RMS.

## Rules
- Every screen root uses `android:background="@drawable/aurora_bg_radial"` and `fitsSystemWindows`.
- Strings in `values/strings.xml` (EN) + `values-ru/strings.xml` (RU). Never hardcode.
- Keep chrome minimal; let the content and the orb carry the screen.
