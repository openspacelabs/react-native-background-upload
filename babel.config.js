// For Jest only. Metro/RN builds use each app's own babel config; the library
// itself ships TypeScript source (no build step).
module.exports = {
  presets: [
    ['@babel/preset-env', { targets: { node: 'current' } }],
    '@babel/preset-typescript',
  ],
};
