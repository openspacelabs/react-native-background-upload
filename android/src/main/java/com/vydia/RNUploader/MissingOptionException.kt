package com.vydia.RNUploader

class MissingOptionException(optionName: String) :
  IllegalArgumentException("Missing '$optionName'")