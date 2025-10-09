/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 *
 * @format
 * @flow
 */

import React, {useEffect, useState} from 'react';
import {
  Button,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import notifee, {AndroidImportance} from '@notifee/react-native';
import {Colors} from 'react-native/Libraries/NewAppScreen';

import Upload, {UploadOptions} from 'react-native-background-upload';

import * as RNFS from 'react-native-fs';

const TEST_FILE = `${RNFS.DocumentDirectoryPath}/1MB.bin`;
const TEST_FILE_URL =
  'https://gist.githubusercontent.com/khaykov/a6105154becce4c0530da38e723c2330/raw/41ab415ac41c93a198f7da5b47d604956157c5c3/gistfile1.txt';
const UPLOAD_URL = 'https://httpbin.org/put/404';
const ANDROID_NOTIFICATION_CHANNEL = 'RNBGUExample';

Upload.initialize({
  notificationId: ANDROID_NOTIFICATION_CHANNEL,
  notificationTitle: ANDROID_NOTIFICATION_CHANNEL,
  notificationTitleNoWifi: 'No wifi',
  notificationTitleNoInternet: 'No internet',
  notificationChannel: ANDROID_NOTIFICATION_CHANNEL,
});

const App = () => {
  const [uploadId, setUploadId] = useState<string>();
  const [progress, setProgress] = useState<number>();
  const [testFileDownload, setTestFileDownload] = useState<
    'downloading' | 'downloaded'
  >();

  useEffect(() => {
    Upload.addListener('progress', null, data => {
      setUploadId(data.uploadId);
      setProgress(data.progress);
      console.log(`Progress: ${data.progress}%`);
    });
    Upload.addListener('error', null, data => {
      console.log(`Error: ${data.error}%`);
    });
    Upload.addListener('completed', null, data => {
      console.log('Completed!', data);
    });
  }, []);

  useEffect(() => {
    notifee.requestPermission({alert: true, sound: true});

    notifee.createChannel({
      id: ANDROID_NOTIFICATION_CHANNEL,
      name: ANDROID_NOTIFICATION_CHANNEL,
      importance: AndroidImportance.LOW,
    });

    RNFS.exists('file://' + TEST_FILE)
      .then(exists => {
        if (exists) return;

        setTestFileDownload('downloading');
        return RNFS.downloadFile({fromUrl: TEST_FILE_URL, toFile: TEST_FILE})
          .promise;
      })
      .then(() => setTestFileDownload('downloaded'));
  }, []);

  const doUpload = async (id?: string) => {
    const customUploadId = id ?? Date.now().toString() + Math.random();
    console.log('---starting', customUploadId);

    const uploadOpts: UploadOptions = {
      customUploadId,
      type: 'raw',
      url: UPLOAD_URL,
      path: TEST_FILE,
      method: 'POST',
      headers: {},
    };

    Upload.startUpload(uploadOpts)
      .then(uploadId => {
        console.log(
          `Upload started with options: ${JSON.stringify(uploadOpts)}`,
        );
        setUploadId(uploadId);
        setProgress(0);
      })
      .catch(function (err) {
        setUploadId(undefined);
        setProgress(undefined);
        console.log('Upload error!', err);
      });
  };

  const onPressUpload = () => {
    for (let i = 0; i < 100; i++) {
      doUpload();
    }
  };

  return (
    <>
      <StatusBar barStyle="dark-content" />
      <SafeAreaView testID="main_screen">
        <View style={{padding: 20}}>
          {testFileDownload === 'downloading' && (
            <Text style={{textAlign: 'center'}}>Downloading test file...</Text>
          )}
        </View>
        {testFileDownload === 'downloaded' && (
          <ScrollView
            contentInsetAdjustmentBehavior="automatic"
            style={styles.scrollView}>
            <View style={styles.body}>
              <View style={styles.sectionContainer}>
                <Button title="Upload" onPress={onPressUpload} />

                <View style={{height: 32}} />
                <Text style={{textAlign: 'center'}}>
                  {`Current Upload ID: ${
                    uploadId === null ? 'none' : uploadId
                  }`}
                </Text>
                <Text style={{textAlign: 'center'}}>
                  {`Progress: ${progress === null ? 'none' : `${progress}%`}`}
                </Text>
                <View />
                <Button
                  testID="cancel_button"
                  title="Cancel Upload"
                  onPress={() => {
                    if (!uploadId) {
                      console.log('Nothing to cancel!');
                      return;
                    }

                    Upload.cancelUpload(uploadId).then(() => {
                      console.log(`Upload ${uploadId} canceled`);
                      setUploadId(undefined);
                      setProgress(undefined);
                    });
                  }}
                />
              </View>
            </View>
          </ScrollView>
        )}
      </SafeAreaView>
    </>
  );
};

const styles = StyleSheet.create({
  scrollView: {
    backgroundColor: Colors.lighter,
  },
  engine: {
    position: 'absolute',
    right: 0,
  },
  body: {
    backgroundColor: Colors.white,
  },
  sectionContainer: {
    marginTop: 32,
    paddingHorizontal: 24,
  },
  sectionTitle: {
    fontSize: 24,
    fontWeight: '600',
    color: Colors.black,
  },
  sectionDescription: {
    marginTop: 8,
    fontSize: 18,
    fontWeight: '400',
    color: Colors.dark,
  },
  highlight: {
    fontWeight: '700',
  },
  footer: {
    color: Colors.dark,
    fontSize: 12,
    fontWeight: '600',
    padding: 4,
    paddingRight: 12,
    textAlign: 'right',
  },
});

export default App;
