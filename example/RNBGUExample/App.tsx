/**
 * Sample React Native App
 * https://github.com/facebook/react-native
 *
 * @format
 * @flow
 */

import React, {useEffect, useState} from 'react';
import {
  SafeAreaView,
  StyleSheet,
  ScrollView,
  View,
  Text,
  StatusBar,
  Button,
  Platform,
  Alert,
} from 'react-native';

import {Colors} from 'react-native/Libraries/NewAppScreen';

import Upload, {UploadOptions} from 'react-native-background-upload';

import {launchImageLibrary} from 'react-native-image-picker';
import {createFormDataFile} from './utils/formdata';

const host = `http://${Platform.OS === 'ios' ? 'localhost' : '10.0.2.2'}:3000`;

const App = () => {
  const [uploadId, setUploadId] = useState<string>();
  const [progress, setProgress] = useState<number>();

  useEffect(() => {
    Upload.addListener('progress', null, data => {
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

  const upload = (
    url: string,
    path: string,
    headers?: UploadOptions['headers'],
  ) => {
    const uploadOpts: UploadOptions = {
      android: {
        notificationId: 'RNBGUExample',
        notificationTitle: 'RNBGUExample',
        notificationTitleNoWifi: 'No wifi',
        notificationTitleNoInternet: 'No internet',
        notificationChannel: 'RNBGUExample',
      },
      type: 'raw',
      url,
      path,
      method: 'POST',
      headers,
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

  const getAsset = async () => {
    const response = await launchImageLibrary({mediaType: 'photo'});
    console.log('ImagePicker response: ', response);
    const {didCancel, errorMessage, assets} = response;
    if (didCancel) return;

    if (errorMessage) {
      console.warn('ImagePicker error:', errorMessage);
      return;
    }

    const asset = assets?.[0];
    const type = asset?.type;
    const path = asset?.uri;
    if (!path) return Alert.alert('Invalid file path');
    if (!type) return Alert.alert('Invalid file type');

    return {path, type} as const;
  };

  return (
    <>
      <StatusBar barStyle="dark-content" />
      <SafeAreaView testID="main_screen">
        <ScrollView
          contentInsetAdjustmentBehavior="automatic"
          style={styles.scrollView}>
          <View style={styles.body}>
            <View style={styles.sectionContainer}>
              <Button
                title="Tap To Upload Multipart"
                onPress={async () => {
                  const asset = await getAsset();
                  if (!asset) return;

                  const {path, contentType} = await createFormDataFile(
                    'formdata_' + new Date().toString(),
                    [
                      {
                        name: 'data',
                        string: JSON.stringify({key: 'value'}),
                        contentType: 'application/json',
                      },
                      {
                        name: 'file',
                        path: asset.path,
                        contentType: asset.type,
                      },
                    ],
                  );

                  upload(`${host}/multipart-upload`, path, {
                    'Content-Type': contentType,
                  });
                }}
              />

              <View style={{height: 32}} />
              <Text style={{textAlign: 'center'}}>
                {`Current Upload ID: ${uploadId === null ? 'none' : uploadId}`}
              </Text>
              <Text style={{textAlign: 'center'}}>
                {`Progress: ${progress === null ? 'none' : `${progress}%`}`}
              </Text>
              <View />
              <Button
                testID="cancel_button"
                title="Tap to Cancel Upload"
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
