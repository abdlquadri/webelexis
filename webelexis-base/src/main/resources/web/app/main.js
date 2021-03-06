/*!
 ** This file is part of Webelexis.
 ** (c) 2015 by G. Weirich
 **/

/*
 * Main view switcher. Initially inspired from https://github.com/lshift/knockout-routing
 */

define(['app/config', 'knockout', 'app/router', 'bootstrap'], function (config, ko, Router) {

	// Initial setup: Register all active components and create a minimal menu consisting of all menu items open for "guest".
	for (var i = 0; i < config.modules.length; i++) {
		var page = config.modules[i]
		if (page.active) {
			ko.components.register(page.component, {
				require: page.location + '/' + page.component
			})

			if (page.menuItem && page.role === "guest") {
				config.mainMenu.push(page)
			}
		}
	}
	// This is the KO ViewModel for the whole page, which contains our router, which
	// in turn keeps track of the current page.
	var topLevelModel = {
		router: new Router()
	};
	// Make model accessible in global context, purely to aid debugging.
	// window.topLevelModel = topLevelModel

	// Need to explicitly bind to 'html' node if we want setting the page title to work.
	ko.applyBindings(topLevelModel, $('html').get(0));


});
