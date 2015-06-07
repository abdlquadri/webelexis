/*
 * This file is part of Webelexis. Copyright (c) 2015 by G. Weirich
 */

/**
 * Created by gerry on 06.06.15.
 */

require.config({
	baseUrl: "js/app",
	paths  : {
		"bootstrap"        : "../lib/bootstrap/bootstrap",
		"jquery"           : "../lib/jquery/jquery",
		"jquery-ui"        : "../lib/jquery-ui/jquery-ui",
		"validate"         : "../lib/jquery-validate/jquery.validate",
		"knockout"         : "../lib/knockout/knockout",
		"sockjs"           : "../lib/sockjs/sockjs",
		"text"             : "../lib/requirejs-text/text",
		"vertxbus"         : "../lib/vertxbus/vertxbus",
		"knockout-jqueryui": "../lib/knockout-jqueryui/knockout-jqueryui",
		"cookie"           : "../lib/js-cookie/js.cookie",
		"durandal"         : "../lib/durandal",
		"plugins"          : "../lib/durandal/plugins",
		"transitions"      : "../lib/durandal/transitions",
		"bus"              : "eb",
		"spark"            : "https://cdnjs.cloudflare.com/ajax/libs/jquery-sparklines/2.1.2/jquery.sparkline.min",
		"underscore"       : "https://cdnjs.cloudflare.com/ajax/libs/underscore.js/1.8.3/underscore-min",
		"flot"             : "https://cdnjs.cloudflare.com/ajax/libs/flot/0.8.3/jquery.flot.min",
		"flot-time"        : "https://cdnjs.cloudflare.com/ajax/libs/flot/0.8.3/jquery.flot.time.min",
		"cke"			         : "https://cdnjs.cloudflare.com/ajax/libs/ckeditor/4.4.5/ckeditor",
		"smooth"           : "../lib/flot.curvedlines/curvedLines",
	},
	shim   : {
		"bootstrap": {
			deps: ["jquery"]
		},
		"knockout" : {
			deps: ["jquery"]
		},
		"validate" : {
			deps: ["jquery"]
		},
		"flot-time": {
			deps: ["flot"]
		},
		"smooth"   : {
			deps: ["flot"]
		},
		"cke"      : {
			deps: ["jquery"]
		}
	}
});

define(['durandal/system', 'durandal/app', 'durandal/viewLocator'],  function (system, app, viewLocator) {
	//>>excludeStart("build", true);
	system.debug(true);
	//>>excludeEnd("build");

	app.title = 'Webelexis';

	//specify which plugins to install and their configuration
	app.configurePlugins({
		router:true,
		dialog: true,
		widget: {
			kinds: ['expander']
		}
	});

	app.start().then(function () {
		viewLocator.useConvention();
		app.setRoot('shell');
	});
});
