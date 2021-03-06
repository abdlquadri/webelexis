/**
 ** This file is part of Webelexis
 ** Copyright (c) by G. Weirich 2015
 */

define(['knockout'], function (ko) {
	var lang = "de" // default language

	var Locale = {
		'de': {
			findapp     : "Terminsuche",
			console     : "Konsole",
			agenda      : "Agenda",
			patients    : "Patienten",
			patient     : "Patient",
			consultation: "Konsultation",
			login       : "Webelexis Anmeldung",
			addpatient  : "Konto erstellen",
			wait        : "Bitte einen Moment Geduld",
			alert       : "Mitteilung",
			verify      : "Kontobestätigung",
			labview     : "Laborwerte"
		}
	}
	var supportedLanguages = ['de', 'en', 'fr']
	var language = window.navigator.language
	if (language !== undefined) {
		if (supportedLanguages.indexOf(language.split("[-_]")[0]) != -1) {
			lang = language
		}
	}
	var R = Locale[lang];

	return {

		locale     : function () {
			return lang
		},
		// not really necessary in standard situations
		eventbusUrl: "http://localhost:2015/eventbus",
		// any page you want to be called with a click on the logo
		homepage   : "http://github.com/rgwch/webelexis",
		// if false: The login field remains hidden
		showLogin  : ko.observable(true),
		sessionID  : "",
		user       : ko.observable({
			"loggedIn": false,
			"roles"   : ["guest"],
			"username": "",
			"id_token": {}
		}),
		connected  : ko.observable(false),
		loc        : {
			ip: "0.0.0.0"
		},
		mainMenu   : [],

		/* Definition of modules to use. Note: The access rights are ultimately defined on the server side.
		 So there's no point in activating a module here, if the user doesn't have respective rights on
		 the server. Doing so would only result in a "dead" module. */
		modules: [{
			match    : /^([0-9]{8,8})?$/, // regexp to match for this page
			title    : R.findapp, // title to display on this page's tab
			component: 'ch-webelexis-agenda', // name pof the component that creates this pages
			location : 'components/agenda', // location of the componant
			active   : true, // to deactivate a component temporarily, set to 'false'
			menuItem : true, // if the component doesn't need a menu item, set to 'false
			baseUrl  : "#", // Only if menuItem is true: Page to load if menu called
			role     : "guest" // which user role is allowed to use this component.
			// again: The server side decides ultimately.
		}, {
			//baseUrl: "#conslist",
			match    : /^conslist\/(.+)\/?$/,
			component: "ch-webelexis-conslist",
			location : "components/conslist",
			menuItem : false,
			role     : "arzt",
			active   : true
		}, {
			//baseUrl: "#labview",
			match    : /^labview\/(.+)\/?$/,
			title    : R.labview,
			component: "ch-webelexis-labview",
			location : "components/labview",
			menuItem : false,
			role     : "arzt",
			active   : true
		}, {
			active   : false,
			title    : R.console,
			match    : /^console$/,
			component: "ch-webelexis-console",
			location : "components/console",
			menuItem : true,
			baseUrl  : "#console",
			role     : "user"
		}, {
			match    : /^agext(\/[0-9]{8,8})?$/,
			title    : R.agenda,
			component: 'ch-webelexis-detailagenda',
			location : 'components/detailagenda',
			active   : true,
			menuItem : true,
			baseUrl  : "#agext",
			role     : "user"
		}, {
			//baseUrl: "#verify",
			match    : /^verify\/([^;,:]+@[a-zA-Z0-9]{2,}\.[a-zA-Z]{2,})\/([a-f0-9-]+)$/,
			title    : R.verify,
			component: 'ch-webelexis-verify',
			location : 'components/verify',
			active   : true,
			menuItem : false,
			role     : "guest"
		}, {
			//baseUrl: "#patid",
			match    : /^patid\/(.+)\/?$/,
			title    : R.patient,
			component: 'ch-webelexis-patdetail',
			location : 'components/patdetail',
			menuItem : false,
			active   : true,
			role     : "user"

		}, {
			//baseUrl: "#kons",
			match    : /^kons\/(.+)\/?$/,
			title    : R.consultation,
			location : 'components/consultation',
			component: 'ch-webelexis-consdetail',
			menuItem : false,
			active   : true,
			role     : "guest"
		}, {
			//baseUrl: "#login",
			match    : /^login$/,
			title    : R.login,
			component: 'ch-webelexis-login',
			location : 'components/login',
			active   : true,
			menuItem : false,
			role     : "guest"
		}, {
			title    : 'page404',
			component: 'ch-webelexis-page404',
			location : 'components/page404',
			active   : true,
			menuItem : false,
			role     : "guest"
		}, {
			component: 'ch-webelexis-menubar',
			location : 'components/menubar',
			active   : true,
			role     : "guest"
		}, {
			title    : R.addpatient,
			//baseUrl: "#addpatient",
			match    : /^addpatient$/,
			component: 'ch-webelexis-addpatient',
			location : 'components/addpatient',
			active   : true,
			role     : "guest",
			menuItem : false
		}, {
			title    : R.wait,
			component: "ch-webelexis-wait",
			location : 'components/wait',
			active   : true,
			role     : 'guest',
			menuItem : false
		}, {
			title    : R.alert,
			//baseURL: "#alert",
			match    : /^alert\/(\w+)\/(\w+)$/,
			component: 'ch-webelexis-alert',
			location : 'components/alert',
			active   : true,
			menuItem : false,
			role     : 'guest'
		}

		]
	}

});
