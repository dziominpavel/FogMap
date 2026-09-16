# ВРЕМЕННЫЙ ФАЙЛ — 10 готовых промптов (удалить после внедрения)

Как пользоваться: копируешь блок целиком в ChatGPT или Nano Banana, жмешь generate, сохраняешь под указанным именем. Ничего дописывать не надо — стиль уже внутри каждого промпта. Всегда проси PNG 1024x1024 (hero — 16:9). Таб-бар и FAB не генерируем — они возьмутся из Material Symbols в коде.

## 1. Сохрани как `launcher-master-1024.png` (в репо НЕ класть, только для Image Asset)

```
Playful cozy mobile game app icon, soft rounded 3D with clean thick outline, single rounded-square fog-of-war map tile, edges dissolving into volumetric mist blue #93C5FD, one glowing mint path #6EE7B7 revealed across the tile, cute little fox explorer with orange fur, teal scarf and round amber lantern #FBBF24 standing on the path, dark slate background #0F1419, adventure mood, soft rim light, centered, readable at 48dp, no photorealism, no text, no letters, no numbers, no watermark, square 1:1, PNG 1024x1024
```

## 2. Сохрани как `launcher-mono-1024.png` (монохром для Themed icons)

```
Monochrome Android themed app icon, pure white #FFFFFF silhouette on fully transparent background, simplified fog-of-war tile with one revealed path and little fox head with lantern, only 2 flat shapes, no gradients, no glow, no shadows, centered in 70 percent safe area, minimal geometric, no text, no letters, no watermark, square 1:1, PNG 1024x1024 transparent
```

## 3. Сохрани как `ic_stat_fog-master.png` (иконка уведомления, только белая)

```
Android notification small icon, pure white #FFFFFF flat fog cloud with a gap of revealed path in the middle, minimal geometric front view, on fully transparent background, no shadows, no gradients, no glow, readable at 24dp, no text, no letters, no watermark, square 1:1, PNG 1024x1024 transparent
```

## 4. Сохрани как `img_empty_map.png` (пустая карта)

```
Playful cozy game empty-state illustration, soft rounded 3D with clean thick outline, top-down map tile covered by thick mist blue fog #93C5FD, one glowing mint path #6EE7B7 revealed with tiny fox footprints, warm amber lantern glow #FBBF24 in the center, dark slate #0F1419 tones, cute little fox explorer with orange fur teal scarf and round lantern peeking from corner, on fully transparent background, no UI, no text, no letters, no watermark, square 1:1, PNG 1024x1024
```

## 5. Сохрани как `img_empty_stats.png` (пустая статистика)

```
Playful cozy game empty-state illustration, soft rounded 3D with clean thick outline, cute little fox explorer with orange fur teal scarf and round lantern holding a giant empty map scroll like a trophy, zero stars muted confetti, mint #6EE7B7 and mist blue #93C5FD accents, dark slate #0F1419 tones, on fully transparent background, achievement empty mood, no text, no letters, no numbers, no watermark, square 1:1, PNG 1024x1024
```

## 6. Сохрани как `img_empty_history.png` (пустая история)

```
Playful cozy game empty-state illustration, soft rounded 3D with clean thick outline, cute little fox explorer with orange fur teal scarf and round lantern looking at an empty open album with dotted trail outlines, one leaf blown by wind, nostalgic mood, mist blue #93C5FD and mint #6EE7B7 accents, dark slate #0F1419 tones, on fully transparent background, no text, no letters, no watermark, square 1:1, PNG 1024x1024
```

## 7. Сохрани как `img_onboarding_geo.png` (онбординг шаг 1)

```
Playful cozy game onboarding illustration, soft rounded 3D with clean thick outline, same cute little fox explorer with orange fur teal scarf and round lantern holding a giant map pin over a glowing mint path #6EE7B7, GPS waves in mint, mist blue fog #93C5FD around, warm amber light #FBBF24, dark slate #0F1419 tones, on fully transparent background, permission step mood, no text, no letters, no watermark, square 1:1, PNG 1024x1024
```

## 8. Сохрани как `img_onboarding_bg.png` (онбординг шаг 2)

```
Playful cozy game onboarding illustration, soft rounded 3D with clean thick outline, same cute little fox explorer with orange fur teal scarf and round lantern sleeping under a blanket while a dotted glowing trail keeps moving behind it, small moon and sleeping Zs, mist blue fog #93C5FD and mint glow #6EE7B7, warm amber night light #FBBF24, dark slate #0F1419 tones, on fully transparent background, background tracking mood, no text, no letters, no watermark, square 1:1, PNG 1024x1024
```

## 9. Сохрани как `img_onboarding_battery.png` (онбординг шаг 3)

```
Playful cozy game onboarding illustration, soft rounded 3D with clean thick outline, same cute little fox explorer with orange fur teal scarf and round lantern hugging a giant battery charging with mint lightning #6EE7B7, simple faceless smartphone shape nearby with no logo, mist blue accents #93C5FD, warm amber light #FBBF24, dark slate #0F1419 tones, on fully transparent background, battery step mood, no text, no letters, no brand logos, no watermark, square 1:1, PNG 1024x1024
```

## 10. Сохрани как `img_hero_fog.png` (широкий баннер статистики)

```
Playful cozy game banner illustration wide 16:9, soft rounded 3D with clean thick outline, panoramic foggy hills, left side dense mist blue fog wall #93C5FD, right side revealed mint valley #6EE7B7 with winding paths, tiny cute fox explorer with orange fur teal scarf and round amber lantern #FBBF24 walking toward light, progress from dark to light left to right, dark slate sky #0F1419, adventure mood, no text, no letters, no watermark, 16:9, PNG 1600x900
```

## Куда класть (коротко)

- `launcher-master-1024.png` + `launcher-mono-1024.png` → Android Studio → res → New → Image Asset → Launcher Icons (Foreground + Monochrome). Сам разложит по `mipmap-*`. Мастера в репо не коммитим.
- `ic_stat_fog-master.png` → нарежь в 24/36/48/72/96 px, белые PNG в `drawable-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi/ic_stat_fog.png`.
- Остальные 7 → конверт в WebP q85, по 800 px (hero 1600x900) в `drawable-nodpi/` с теми же именами, расширение `.webp`.
- SVG не нужен. После раскладки этот файл удалить.
