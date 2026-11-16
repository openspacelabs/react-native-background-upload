module.exports = {
  preset: 'react-native',
  // The library source in ../../src imports react. Without this it resolves
  // the repo root's copy, and the test renderer's copy here is another React,
  // so hooks fail.
  moduleNameMapper: {
    '^react$': '<rootDir>/node_modules/react',
  },
};
