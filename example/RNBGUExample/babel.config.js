const pak = require('../../package.json');

module.exports = {
  presets: ['module:@react-native/babel-preset'],
  plugins: [
    [
      'module-resolver',
      {
        root: ['.'],
        extensions: ['.js', '.jsx', '.ts', '.tsx'],
        alias: {
          [pak.name]: '../../src',
        },
      },
    ],
  ],
};
