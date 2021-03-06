/*
 * This file is part of Webelexis. Copyright (c) 2015 by G. Weirich
 */

/**
 * Created by gerry on 14.06.15.
 */

define(['knockout', 'underscore'], function (ko, _) {
  var defconf = {
    "mongo": {
      "address": "ch.webelexis.nosql", // do not change this
      "host": "localhost", // address of your mongo server. defaults to 'localhost'
      "port": 27017, // optional
      "username": "mongodb-user", // optional
      "password": "mongodb-password", // optional
      "db_name": "webelexis", // do not change
      "pool_size": 20, // optional
      "use_ssl": false, // only necessary, if access from outside
      "read_preference": "nearest", //  or "primary" etecetera>, optional
      "use_mongo_types": false, // optional; "false" is recommended
      "socket_timeout": 60000, // optional
      "auto_connect_retry": true // optional, "true" recommended
    },

    // config for mod-mysql-postgresql (https://github.com/vert-x/mod-mysql-postgresql)
    "sql": {

      "address": "ch.webelexis.sql", // do not change this
      "connection": "MySQL", // (or "PostgreSQL"),
      "host": "localhost", // address of the database server
      "port": 3306, // optional. Port of the database server. 3306 is default for mysql
      "maxPoolSize": 20, // optional. maximum number of open connections; optional
      "username": "database-user",
      "password": "database-password",
      "database": "elexis" // or whatever
    },

    // config for vertx-mod-sessionmgt (https://github.com/rgwch/vertx-mod-sessionmgr)
    "auth": {
      "address": "ch.webelexis.session", // do not change this
      "users_collection": "users", // do not change this
      "persistor_address": "ch.webelexis.nosql", // do not change this
      "session_timeout": 300000 // optional. Here: 5 Minutes
    },

    // config for webelexis-Agenda
    "agenda": {
      "public": { // public accessible version. If you don't want public access, set "role" to "user" or to "nobody".
        "resource": "gerry", // which calendar resource should be used
        "timeSlot": 30, // how long should an appointment be
        "maxPerDay": 4, // maximum Number of appnts. per day via this app
        "apptType": "Normal", // "TerminTyp" for our appointments
        "apptState": "Via Internet", // "TerminStatus" for our appointments
        "role": "guest" // role needed to access this agende
      },
      "private": { // version for authorized access with patient details
        "resources": [{ // which calendar resources should be offered to chose
          "resource": "gerry", // internal name of the resource
          "title": "G. Weirich" // Name of the resource as to display in the frontemd
        }, {
          "resource": "labor",
          "title": "Labor"
        }],
        "role": "mpa" // role needed to access this agenda
      }

    },
    "account": {
      "allow-creation": true,
      "role": "guest", // role needed to create an account
      "defaultRole": "patient", // role, a newly created account will have

      "confirm-mail": false, // true to send confirmation mail on account creation
      "mails": { // only needed if confirm-mail is true
        "from": "me@mail.example.com", // sender of the confirmation mail
        "bcc": "archive@mail.example.com",
        "activation_subject": "Confirm Webelexis account",
        "activation_body": "To activate your account, please use this activation code: %activationcode%",
        "lostpwd_subject": "Your new password",
        "lostpwd_body": "Your new password is: %password%"
      }
    },
    "mailer": {
      "active": false, // if false: Do not load mail module, do not send mails.
      "address": "ch.webelexis.mailer", // do not change this
      "host": "smpt.example.com", // URL of your mail provider
      "port": 25, // optional
      "ssl": false, // optional, defaults to false
      "auth": true, // optional, defaults to false
      "username": "user", // username for auth
      "password": "password", // password for auth
      "content-type": "text/plain" // optional. Or text/html to send html mails
    },
    "emr": { // config for the electronic medical record access
      "icpc-problems": true, // load problem list (Plugin ICPC is installed)
      "stickynotes": true, // load sticky notes (Plugin "haftnotizen" is installed)
      "stickers": true, // load stickers
      "prescriptions": true, // load prescriptions
      "lab": true, // load lab values
      "consultations": true,
      "role": "arzt" // role needed to read patient details
    },

    // config for the EventBus-Websocket bridge and the html server
    "bridge": {
      "webroot": "web", // Base path. Optional, this is the default
      "port": 2015, // default port for webelexis
      "inOK": [ // allowed inbound messages (all other will be blocked)
        {
          "address": "ch.webelexis.session.login"
        }, {
          "address": "ch.webelexis.session.logout"
        }, {
          "address": "ch.webelexis.publicagenda"
        }, {
          "address": "ch.webelexis.privateagenda"
        }, {
          "address_re": "ch\\.webelexis\\.patient\\.\\w+"
        }
      ], // allowed outbound messages
      "outOK": [{
        "address_re": "ch\\.webelexis\\.feedback\\.[a-f0-9-]+"
      }],
      // remove the following property, if you don't want "Sign in with google"
      "googleID": "3aa2352634636aasf.apps.googleusercontent.com", // Google client ID if Google SignIn is used.
      "ssl": false, // use tls/ssl/https. Strongly recommended
      "keystore": "/some/where/ks.jks", // filepath of the keystore. Defaults to $HOME/.jkeys/keystore.jks
      "keystore-pwd": "something" // password of the jks keystore and the server certificate/key
    },
    // Global identification primarly for debugging purposes to see, which config is active.
    "id": "this is an identification for this config"
  }
})
