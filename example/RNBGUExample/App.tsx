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

  const onPressUpload = (url: string) => {
    launchImageLibrary(
      {
        mediaType: 'photo',
      },
      response => {
        console.log('ImagePicker response: ', response);
        const {didCancel, errorMessage, assets} = response;
        if (didCancel) {
          return;
        }
        if (errorMessage) {
          console.warn('ImagePicker error:', errorMessage);
          return;
        }

        const asset = assets?.[0];
        const path =
          Platform.OS === 'android' ? asset?.originalPath : asset?.uri;
        if (!path) {
          return Alert.alert('Invalid path');
        }
        if (!asset?.type) {
          return Alert.alert('Invalid file type');
        }

        // Video is stored locally on the device
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
          headers: {
            'Content-Type': asset.type || '',
          },
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
      },
    );
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
                onPress={() =>
                  onPressUpload(
                    `http://${
                      Platform.OS === 'ios' ? 'localhost' : '10.0.2.2'
                    }:3000/multipart-upload`,
                  )
                }
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
