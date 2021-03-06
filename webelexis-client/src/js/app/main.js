/*
 * This file is part of Webelexis. Copyright (c) 2015 by G. Weirich
 */

/**
 * Created by gerry on 06.06.15.
 */

/*
 Configuration for RequireJS
 */
require.config({
  baseUrl: "js/app",
  paths: {
    "bootstrap": "../lib/bootstrap/bootstrap",
    "jquery": "../lib/jquery/jquery",
    "jquery-ui": "../lib/jquery-ui/jquery-ui",
    "validate": "../lib/jquery-validate/jquery.validate",
    "knockout": "../lib/knockout/knockout",
    "sockjs": "../lib/sockjs/sockjs",
    "text": "../lib/requirejs-text/text",
    "vertxbus": "../lib/vertxbus-3.0.0",
    "knockout-jqueryui": "../lib/knockout-jqueryui/knockout-jqueryui",
    "cookie": "../lib/js-cookie/js.cookie",
    "durandal": "../lib/durandal",
    "plugins": "../lib/durandal/plugins",
    "transitions": "../lib/durandal/transitions",
    "i18n": "../lib/i18next/i18next.amd.withJQuery",
    "bus": "eb",
    "spark": "https://cdnjs.cloudflare.com/ajax/libs/jquery-sparklines/2.1.2/jquery.sparkline.min",
    "underscore": "../lib/underscore/underscore",
    "flot": "https://cdnjs.cloudflare.com/ajax/libs/flot/0.8.3/jquery.flot.min",
    "flot-time": "https://cdnjs.cloudflare.com/ajax/libs/flot/0.8.3/jquery.flot.time.min",
    "cke": "https://cdnjs.cloudflare.com/ajax/libs/ckeditor/4.4.5/ckeditor",
    //"cke": "../lib/ckeditor/ckeditor",
    "smooth": "../lib/flot.curvedlines/curvedLines"
  },
  shim: {
    "bootstrap": {
      deps: ["jquery"]
    },
    "knockout": {
      deps: ["jquery"]
    },
    "validate": {
      deps: ["jquery"]
    },
    "flot-time": {
      deps: ["flot"]
    },
    "smooth": {
      deps: ["flot"]
    },
    "cke": {
      deps: ["jquery"]
    }
  }
});

/*
 Entry point for the Durandal framework
 */
define(['durandal/system', 'durandal/app', 'durandal/viewLocator', 'i18n', 'durandal/binder'], function (system, app, viewLocator, i18n, binder) {
  //>>excludeStart("build", true);
  system.debug(true);
  //>>excludeEnd("build");

  /*
   Configuration for i18next
   */
  var i18NOptions = {
    detectFromHeaders: false,
    lng: window.navigator.userLanguage || window.navigator.language || 'de-CH',
    fallbackLng: 'de',
    ns: {
      namespaces: ['base', 'datepicker', 'alerts'],
      defaultNs: 'base'
    },
    resGetPath: 'locales/__ns__-__lng__.json',
    useCookie: false,
    useLocalStorage: false,
    fallbackOnNull: true,
    fallbackOnEmpty: true
  };

  app.title = 'Webelexis';

  //specify which plugins to install and activate widgets
  app.configurePlugins({
    router: true,
    dialog: true,
    widget: {
      kinds: ['expander', 'datepicker', 'summary']
    }
  });

  /*
   Initialize the Durandal app, the i18next-localization, the Durandal binding aystem and then launch shell.js/shell.jade as
   the root view.
   */
  app.start().then(function () {
    i18n.init(i18NOptions, function () {
      //Call localization on view before binding...
      binder.binding = function (obj, view) {
        $(view).i18n();
      };
      app.setRoot('shell');
    })
  });
});
