exports.config =
  minMimosaVersion: '2.0.0'


  modules: [
    'jade'
    'client-jade-static'
    'copy'
    'require'
    'minify-js'
    'minify-css'
    'minify-html'
  ]

  groundskeeper: {
    log: true
    options: {
      namespace: ["system.debug", "system.log"]
    }
  }
  copy:
    exclude: [
      /.+_spec.js/
    ]

  watch:
    sourceDir: 'src'
    compiledDir: 'dist'
    javascriptDir: 'js/app'

  vendor:
    javascripts: 'js/lib'


  clientJadeStatic:
    outputExtension: 'html',
    extensionRegex: /\.jade$/,
    prettyOutput: false

  jade:
    compileOptions:
      compileDebug: false

  requireBuildTextPluginInclude:
    pluginPath: 'text'
    extensions: ['html']

  requireBuildInclude:
    folder: "js"
    patterns: ['app/**/*.js', 'lib/durandal/**/*.js']

  bower:
    bowerDir:
      clean: false
    copy:
      mainOverrides:
        "durandal": [
          {
            img: "../../images"
            js: "durandal"
            css: "durandal"
          }
        ],
        "i18next": [
          "i18next.amd.withJQuery.js"
        ]



  combine:
    sourceMap: true
    folders: [
      {
        folder: 'stylesheets'
        output: 'stylesheets/styles.css'
        order: [
          'vendor/bootstrap/bootstrap.css'
          'vendor/bootstrap/bootstrap-responsive.css'
          'vendor/font-awesome/font-awesome.css'
          'vendor/durandal/durandal.css'
          'starterkit.css'
          'webelexis.css'
        ],
        exclude: [
          "vendor/bootstrap/bootstrap.min.css",
          "vendor/bootstrap/bootstrap-theme.css",
          "vendor/bootstrab/bootstrap-theme.min.css",
          "vendor/font-awesome/font-awesome.min.css"
        ]
      }
    ]
    removeCombined: {
      enabled: true
    }

  server:
    defaultServer:
      enabled: true
      onePager: true
    views:
      compileWith: 'jade'
      extension: 'html.jade'
      path: "src"

  require: {
    optimize: {
      overrides: {
        name: '../lib/almond-custom'
        inlineText: true
        stubModules: ['text']
        pragmas:
          build: true
      }
    }
  }



