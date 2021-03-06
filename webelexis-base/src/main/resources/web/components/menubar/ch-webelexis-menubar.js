/**
 ** This file is part of Webelexis
 ** Copyright (c) 2015 by G. Weirich
 **/
define(['app/config', 'knockout', 'text!tmpl/ch-webelexis-menubar.html', 'bus', 'knockout-jqueryui/dialog'], function(cfg, ko, html, bus) {

  var Locale = {
    de: {
      connected: "mit Server verbunden",
      notconnected: "vom Server getrennt",
      login: "Anmelden",
      logout: "Abmelden",
      chpwd: "Passwort ändern",
      chpwdTitle: "Passwort ändern",
      chpwdOldpw: "Altes Passwort",
      chpwdNewpw: "Neues Passwort",
      chpwdNewpwRep: "Neues Password wiederholen",
      chpwdSubmit: "Absenden"
    }
  }
  var clientID = $("meta[name='clientID']").attr("content")
  cfg.sessionID = $("meta[name='UUID']").attr("content")
  var state = $("meta[name='state']").attr("content")

  var R = Locale[cfg.locale()]

  function MenubarModel(params) {
    var self = this
    self.menuItems = ko.observableArray(cfg[params.menu])
    self.unam = ko.observable()
    self.upwd = ko.observable()

    self.locale = function(varb) {
        return R[varb]
      }
      // subscribe for changes of user
    cfg.user.subscribe(function() {
      self.adaptForUser()
    })

    self.connected = ko.pureComputed(function() {
      return cfg.connected()
    })
    self.showLogin = ko.computed(function() {
      if (cfg.showLogin()) {
        if (!cfg.user().loggedIn) {
          return true
        }

      }
      return false
    })

    self.showLogout = ko.computed(function() {
      if (cfg.showLogin()) {
        if (cfg.user().loggedIn) {
          return true
        }
      }
      return false
    })


    self.doLogout = function() {
      if (cfg.google !== undefined) {
        cfg.google.signOut()
      }
      bus.send("ch.webelexis.session.logout", {
        sessionID: cfg.sessionID
      }, function(result) {
        bus.clearFeedbackAddress()
        cfg.user({
          "loggedIn": false,
          "roles": ["guest"],
          "username": ""
        })
        self.unam("")
        self.upwd("")
        self.adaptForUser()
        if (result.status !== "ok") {
          console.log("Problem beim Abmelden " + result.message)
        }
        window.location.hash = "#"
        window.location.reload()
      })
    }

    self.adaptForUser = function() {
      //console.log(JSON.stringify(cfg.user()))
      self.menuItems.removeAll()
      for (var i = 0; i < cfg.modules.length; i++) {
        var item = cfg.modules[i]
        if (item.menuItem && item.active) {
          if ((item.role === "guest") || (cfg.user().roles.indexOf(item.role) !== -1)) {
            self.menuItems.push(item)
          }
        }
      }
      if (cfg.user().loggedIn) {
        $('#displayName').html(cfg.user().username + "<span class='caret'></span>")
      }

    }


    self.signInChanged = function(val) {
      console.log('Signin state changed to ', val);
      cfg.user().loggedIn = val

    }

    // google user signed in
    self.userChanged = function(user) {
      if ((user !== undefined) && (user.getId() !== null) && (user.getAuthResponse() !== null)) {
        console.log("user now: " + user.getId() + ", " + user.getBasicProfile().getName());
        bus.send("ch.webelexis.session.login", {
          "mode": "google",
          "sessionID": cfg.sessionID,
          "username": user.getBasicProfile().getEmail(),
          "userid": user.getBasicProfile().getId(),
          "realname": user.getBasicProfile().getName(),
          "id_token": user.getAuthResponse().id_token,
          "client_id": clientID,
          "state": state,
          "feedback-address": "ch.webelexis.feedback." + cfg.sessionID
        }, function(result) {
          if (result.status === undefined) {
            window.alert("Verbindungsfehler")
          } else if (result.status === "unknown user") {
            cfg.google.signOut()
              //window.alert("Dieser google user ist an diesem System nicht bekannt. Bitte melden Sie sich zunächst an.")
            window.location.hash = "#alert/ghead/gbody"
          } else if (result.status === "ok") {
            $.extend(true, user, result.user)
            user.loggedIn = true;
            bus.setFeedbackAddress()
            cfg.user(user)
            window.location.hash = "#"
          } else {
            window.alert("userchange error: "+result.status+" "+result.message)
          }

        })
      } else {
        console.log("user now: nobody");
        cfg.user({
          "loggedIn": false,
          "username": "",
          "access_token": {},
          "id_token": {},
          "email": "",
          "userid": "",
          "roles": ["guest"]
        })
      }
    }

    self.refreshValues = function() {
      self.adaptForUser()
    }

    self.initSigninV2 = function() {
      cfg.google = window.gapi.auth2.init({
        client_id: clientID,
        cookiepolicy: 'single_host_origin'
      })


      cfg.google.isSignedIn.listen(self.signInChanged);
      cfg.google.currentUser.listen(self.userChanged);

      // Sign in the user if they are currently signed in.
      if (cfg.google.isSignedIn.get() === true) {
        cfg.google.signIn();
      }

      // Start with the current live values.
      self.refreshValues();
    }

    /*************
    change password
    *********/
    self.pwdDialogOpen = ko.observable(false)

    self.changePwd = function() {
      self.pwdDialogOpen(true)
    }
    self.submitChangePwd = function() {
        var p = "#chpwd"
        var old = $(p + " #oldpwd").val()
        var new1 = $(p + " #newpwd").val()
        var new2 = $(p + " #newpwdrep").val()
        if (new1 !== new2) {
          window.alert("Das neue Passwort wurde nicht zweimal identisch eingegeben")
        } else {

          bus.send("ch.webelexis.patient.changepwd", {
            username: cfg.user().username,
            "old-pwd": old,
            "new-pwd": new1
          }, function(result) {
            if (result.status === "ok") {
              self.pwdDialogOpen(false);
              window.location.hash = "#alert/pwdheading/pwdbody"
            } else {
              if (result === undefined || result.status === undefined) {
                window.alert("Server error.");
              } else {
                if (result.status === "error" && result.message === "bad password") {
                  window.alert("Sie haben das falsche Passwort eingegeben")
                } else {
                  window.alert("Unbekannter Fehler")
                  console.log(JSON.stringify(result))
                }
              }
            }
          })
        }
      }
      /* Initialize 'Signin with Google', if we have a Google client ID */
    if (clientID !== undefined && clientID !== "x-undefined") {
      window.gapi.load('auth2', self.initSigninV2)
    }
  }
  return {
    viewModel: MenubarModel,
    template: html
  }
})
