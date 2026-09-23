import QtQuick
import qs.Commons
import qs.Ui
import "today.js" as Today

Panel {
  id: root
  moduleName: "raja.pomo"
  ipcTarget: "raja.pomo"
  manageIpc: false

  property var anchorItem: null
  property var hostWidget: null
  property bool replacePairingRequested: false
  readonly property var barIdentity: hostWidget || root
  readonly property var pomo: bar && bar.shell ? bar.shell.serviceFor("raja.pomo") : null
  readonly property bool hasSavedPairing: !!(pomo && pomo.hasToken)
  readonly property bool pairingFormVisible: !hasSavedPairing || replacePairingRequested

  readonly property color contentForeground: bar ? bar.foreground : Color.foreground
  readonly property color urgent: bar ? bar.urgent : Color.urgent
  readonly property color dim: Qt.darker(contentForeground, 1.55)
  readonly property string contentFontFamily: bar ? bar.fontFamily : Style.font.family

  function open() {
    syncPairingFields()
    root.controller.show()
    Qt.callLater(function() {
      if (root.opened) setCenterHoverRevealSuppressed(true)
    })
  }

  function close() {
    replacePairingRequested = false
    setCenterHoverRevealSuppressed(false)
    root.controller.hide()
  }

  function toggle() {
    if (root.opened) root.close()
    else root.open()
  }

  function switchPanel(direction) {
    if (root.bar && typeof root.bar.switchPanelFrom === "function")
      return root.bar.switchPanelFrom(root.barIdentity, direction)
    return false
  }

  function setCenterHoverRevealSuppressed(value) {
    if (root.bar && "centerHoverRevealSuppressed" in root.bar)
      root.bar.centerHoverRevealSuppressed = value
  }

  function persistSettings(values) {
    var entry = { id: root.moduleName }
    for (var existing in root.settings)
      if (existing !== "id") entry[existing] = root.settings[existing]
    for (var key in values) entry[key] = values[key]
    root.settings = entry
    if (root.hostWidget && "settings" in root.hostWidget) root.hostWidget.settings = entry
    if (root.bar && root.bar.shell && typeof root.bar.shell.updateEntryInline === "function")
      root.bar.shell.updateEntryInline(root.moduleName, entry)
  }

  function syncPairingFields() {
    if (hostField.text === "" && pomo && pomo.host) hostField.text = pomo.host
    if (pomo && pomo.port) portField.text = String(pomo.port)
    if (tokenField.text === "" && hasSavedPairing)
      tokenField.placeholderText = "Token saved. Enter a new token to replace it."
  }

  function beginReplacePairing() {
    replacePairingRequested = true
    syncPairingFields()
    pairingField.text = ""
    tokenField.text = ""
    pairingField.placeholderText = "Paste new {url, token} JSON"
    tokenField.placeholderText = "New pairing token"
  }

  function savePairing() {
    var paste = pairingField.text.trim()
    var host = hostField.text.trim()
    var portText = portField.text.trim()
    var token = tokenField.text.trim()
    var fields = {}
    if (paste !== "") {
      // Pasted {url,token} pins the phone. Do not send empty host / default
      // 9876 alongside it — those would override the URL.
      fields.pairingJson = paste
      if (token !== "") fields.token = token
    } else {
      if (host !== "") fields.host = host
      if (portText !== "") {
        var port = parseInt(portText, 10)
        if (port >= 1 && port <= 65535) fields.port = port
      }
      if (token !== "") fields.token = token
    }
    if (Object.keys(fields).length === 0) return
    if (pomo) pomo.applyPairing(fields)
    var persist = {}
    if (paste !== "") {
      var fromPaste = hostPortFromPairingJson(paste)
      if (fromPaste.host) persist.host = fromPaste.host
      // Same 1..65535 range ConfigStore.set_pairing() enforces; a paste can
      // carry any number.
      if (fromPaste.port >= 1 && fromPaste.port <= 65535) persist.port = fromPaste.port
    } else {
      persist.host = host
      if (portText !== "") {
        var persistPort = parseInt(portText, 10)
        if (persistPort >= 1 && persistPort <= 65535) persist.port = persistPort
      }
    }
    if (Object.keys(persist).length > 0) persistSettings(persist)
    replacePairingRequested = false
    tokenField.text = ""
    pairingField.text = ""
    tokenField.placeholderText = "Token saved"
  }

  function hostPortFromPairingJson(text) {
    var out = { host: "", port: 0 }
    try {
      var parsed = JSON.parse(String(text || ""))
    } catch (error) {
      return out
    }
    if (!parsed || typeof parsed !== "object" || !parsed.url) return out
    var rest = String(parsed.url).replace(/^https?:\/\//i, "").replace(/\/.*$/, "")
    if (rest.charAt(0) === "[") {
      var end = rest.indexOf("]")
      if (end > 0) {
        out.host = rest.substring(1, end)
        if (rest.charAt(end + 1) === ":") out.port = parseInt(rest.substring(end + 2), 10) || 0
      }
      return out
    }
    var colon = rest.lastIndexOf(":")
    if (colon > 0) {
      out.host = rest.substring(0, colon)
      out.port = parseInt(rest.substring(colon + 1), 10) || 0
    } else {
      out.host = rest
    }
    return out
  }

  function todayLine() {
    if (!pomo) return ""
    return Today.formatTodayLine(pomo.completed, pomo.goal, pomo.date, pomo.localToday)
  }

  KeyboardPanel {
    id: panel
    anchorItem: root.anchorItem
    owner: root.barIdentity
    bar: root.bar
    open: root.opened
    // Keep the popup attached to the widget that opened it. The base
    // KeyboardPanel clamps the card to the screen edge, so a right-side
    // bar button opens a right-aligned panel instead of pulling focus to
    // the screen center.
    centerOnBar: false
    focusTarget: keyCatcher
    contentWidth: panel.fittedContentWidth(Style.space(360))
    contentHeight: panel.fittedContentHeight(contentColumn.implicitHeight)

    PanelKeyCatcher {
      id: keyCatcher
      anchors.fill: parent
      blocked: hostField.activeFocus || portField.activeFocus || tokenField.activeFocus || pairingField.activeFocus
      onCloseRequested: root.close()
      onTabRequested: function(direction) { root.switchPanel(direction) }
      // Keyboard commands must respect the same busy gate as the buttons:
      // the engine holds stdin gestures while a phone operation is in
      // flight, so a key press could otherwise land right after it.
      onActivateRequested: if (root.pomo && !root.pomo.busy) root.pomo.toggle()
      onTextKey: function(t) {
        if (!root.pomo || root.pomo.busy) return
        var key = String(t || "").toLowerCase()
        if (key === " ") root.pomo.toggle()
        else if (key === "s") root.pomo.skip()
        else if (key === "r") root.pomo.reset()
        else if (key === "e") root.pomo.extend()
      }

      Flickable {
        id: panelFlick
        anchors.fill: parent
        contentWidth: width
        contentHeight: contentColumn.implicitHeight
        clip: true
        boundsBehavior: Flickable.StopAtBounds
        flickableDirection: Flickable.VerticalFlick
        interactive: contentHeight > height

        Column {
          id: contentColumn
          width: panelFlick.width
          spacing: Style.space(12)

          PanelHero {
            width: parent.width
            title: root.pomo ? root.pomo.mmss(root.pomo.remaining) : "00:00"
            meta: (root.pomo ? root.pomo.phaseName(root.pomo.phase, root.pomo.status) : "Focus")
              + (root.pomo ? root.pomo.marker : ".")
            detail: root.todayLine()
            foreground: root.contentForeground
            fontFamily: root.contentFontFamily
            iconComponent: Component {
              Text {
                text: "󰔟"
                color: root.contentForeground
                font.family: root.contentFontFamily
                font.pixelSize: Style.font.display
              }
            }
          }

          Text {
            width: parent.width
            text: root.pomo ? root.pomo.connectionLabel() : "Starting"
            color: root.pomo && root.pomo.mode === "UNPAIRED" ? root.urgent : root.dim
            font.family: root.contentFontFamily
            font.pixelSize: Style.font.bodySmall
            wrapMode: Text.WordWrap
          }

          Text {
            visible: root.pomo && (root.pomo.message !== "" || root.pomo.lastError !== "")
            width: parent.width
            text: root.pomo ? (root.pomo.message !== "" ? root.pomo.message : root.pomo.lastError) : ""
            color: root.dim
            font.family: root.contentFontFamily
            font.pixelSize: Style.font.caption
            wrapMode: Text.WordWrap
          }

          Row {
            width: parent.width
            spacing: Style.space(7)

            Button {
              width: (parent.width - parent.spacing * 3) / 4
              text: root.pomo ? root.pomo.toggleLabel() : "Start"
              foreground: root.contentForeground
              fontFamily: root.contentFontFamily
              bordered: true
              enabled: root.pomo !== null && !root.pomo.busy
              opacity: enabled ? 1 : 0.4
              onClicked: if (root.pomo) root.pomo.toggle()
            }

            Button {
              width: (parent.width - parent.spacing * 3) / 4
              text: "Skip"
              foreground: root.contentForeground
              fontFamily: root.contentFontFamily
              bordered: true
              enabled: root.pomo !== null && !root.pomo.busy
              opacity: enabled ? 1 : 0.4
              onClicked: if (root.pomo) root.pomo.skip()
            }

            Button {
              width: (parent.width - parent.spacing * 3) / 4
              text: "Reset"
              foreground: root.contentForeground
              fontFamily: root.contentFontFamily
              bordered: true
              enabled: root.pomo !== null && !root.pomo.busy
              opacity: enabled ? 1 : 0.4
              onClicked: if (root.pomo) root.pomo.reset()
            }

            Button {
              width: (parent.width - parent.spacing * 3) / 4
              text: "+5m"
              foreground: root.contentForeground
              fontFamily: root.contentFontFamily
              bordered: true
              // Local extend no-ops while not running; the button must not
              // look live.
              enabled: root.pomo !== null && !root.pomo.busy && root.pomo.status === "running"
              opacity: enabled ? 1 : 0.4
              onClicked: if (root.pomo) root.pomo.extend()
            }
          }

          PanelSeparator {
            foreground: root.contentForeground
          }

          Column {
            width: parent.width
            spacing: Style.space(8)

            PanelSectionHeader {
              text: "PAIRING"
              foreground: root.contentForeground
              fontFamily: root.contentFontFamily
            }

            Text {
              width: parent.width
              // Pairing hint reuses existing service props only (host/port/
              // hasToken/mode): pinned host:port vs discovering, no new props.
              text: root.hasSavedPairing
                ? ("Token saved" + (root.pomo.host
                    ? " · " + root.pomo.host + ":" + root.pomo.port + " (pinned)"
                    : " · discovering phone" + (root.pomo.mode ? " (" + root.pomo.mode + ")" : "")))
                : "No token saved"
              color: root.dim
              font.family: root.contentFontFamily
              font.pixelSize: Style.font.caption
              wrapMode: Text.WordWrap
            }

            Button {
              visible: root.hasSavedPairing && !root.replacePairingRequested
              width: parent.width
              text: "Replace pairing"
              foreground: root.contentForeground
              fontFamily: root.contentFontFamily
              bordered: true
              onClicked: root.beginReplacePairing()
            }

            Column {
              visible: root.pairingFormVisible
              width: parent.width
              spacing: Style.space(8)

              TextField {
                id: pairingField
                width: parent.width
                placeholderText: "Paste {url, token} from Pomo Settings"
                foreground: root.contentForeground
                font.family: root.contentFontFamily
              }

              Row {
                width: parent.width
                spacing: Style.space(8)

                TextField {
                  id: hostField
                  width: parent.width - portField.width - parent.spacing
                  placeholderText: "Host (optional, skips mDNS)"
                  foreground: root.contentForeground
                  font.family: root.contentFontFamily
                }

                TextField {
                  id: portField
                  width: Style.space(70)
                  placeholderText: "9876"
                  text: "9876"
                  foreground: root.contentForeground
                  font.family: root.contentFontFamily
                  inputMethodHints: Qt.ImhDigitsOnly
                }
              }

              TextField {
                id: tokenField
                width: parent.width
                password: true
                placeholderText: "Pairing token"
                foreground: root.contentForeground
                font.family: root.contentFontFamily
              }

              Button {
                width: parent.width
                text: "Save pairing"
                foreground: root.contentForeground
                fontFamily: root.contentFontFamily
                bordered: true
                onClicked: root.savePairing()
              }
            }
          }
        }
      }
    }
  }
}
