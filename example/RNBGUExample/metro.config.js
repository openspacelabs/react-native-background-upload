const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');
const path = require('path');
const pak = require('../../package.json');

/**
 * Metro configuration
 * https://reactnative.dev/docs/metro
 *
 * @type {import('metro-config').MetroConfig}
 */
const config = {
  watchFolders: [path.resolve(__dirname, '../../src')],
  resolver: {
    extraNodeModules: {
      [pak.name]: path.resolve(__dirname + '/../../src'),
    },
    nodeModulesPaths: [path.resolve(path.join(__dirname, './node_modules'))],
  },
};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
