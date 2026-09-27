// The site's name, logo and colours, in one place.
//
// The marketplace needs a new name before it takes real users: the
// sboxmarket.com and .gg domains are taken, and a name built on "s&box" is a
// trademark risk. To rebrand the app, change the values below; every page
// title, the nav, the footer and the in-app copy read `BRAND.name` from here.
// (The static legal pages, index.html's <meta> tags and the server-rendered
// share cards still carry the old name and need the same edit.)
export const BRAND = Object.freeze({
  name: 'SkinBox',
  // null keeps the built-in crate mark; set a path such as '/img/logo.svg'
  // to use an image file instead.
  logoUrl: null,
  // Primary button and link colour, and its hover/pressed shade.
  accent: '#237bff',
  accentDark: '#1668e6',
});

// Push the colours into the stylesheet. A plain :root rule, so the theme
// picker's html[data-accent=...] rules still win when a user picks one.
export function applyBrand() {
  if (typeof document === 'undefined') return;
  const style = document.createElement('style');
  style.id = 'brand-tokens';
  style.textContent = `:root { --accent: ${BRAND.accent}; --accent-d: ${BRAND.accentDark}; --cta: ${BRAND.accent}; --cta-d: ${BRAND.accentDark}; --blue: ${BRAND.accent}; --blue-d: ${BRAND.accentDark}; }`;
  document.head.appendChild(style);
}
