/**
 *  Polling Cree Bulb 1.1.2
 *
 *  Author: 
 *     Kevin LaFramboise (krlaframboise)
 *
 *	Changelog: 
 *
 *	1.1.2 (05/13/2016)
 *    - Completely re-wrote the Cree Bulb Device Handler.
 *    - Included self polling feature.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
 
metadata {
	definition (name: "Polling Cree Bulb", namespace: "krlaframboise", author: "Kevin LaFramboise") {
		capability "Actuator"
    capability "Configuration"
		capability "Refresh"
		capability "Switch"
		capability "Switch Level"
		capability "Polling"
		
		attribute "lastPoll", "number"
		
		fingerprint profileId: "C05E", inClusters: "0000,1000,0004,0003,0005,0006,0008", outClusters: "0000,0019"
	}

	// simulator metadata
	simulator {
		// status messages
		status "on": "on/off: 1"
		status "off": "on/off: 0"

		// reply messages
		reply "zcl on-off on": "on/off: 1"
		reply "zcl on-off off": "on/off: 0"
	}
	
	preferences {
		input "debugOutput", "bool", 
			title: "Enable debug logging?", 
			defaultValue: false, 
			displayDuringSetup: false,
			required: false	
	}
	
	tiles(scale: 2) {
		multiAttributeTile(name:"switch", type: "lighting", width: 6, height: 4){
			tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
				attributeState "on", label:'${name}', action:"switch.off", icon:"st.switches.light.on", backgroundColor:"#79b821", nextState:"turningOff"
				attributeState "off", label:'${name}', action:"switch.on", icon:"st.switches.light.off", backgroundColor:"#ffffff", nextState:"turningOn"
				attributeState "turningOn", label:'${name}', action:"switch.off", icon:"st.switches.light.on", backgroundColor:"#79b821", nextState:"turningOff"
				attributeState "turningOff", label:'${name}', action:"switch.on", icon:"st.switches.light.off", backgroundColor:"#ffffff", nextState:"turningOn"
			}
			tileAttribute ("device.level", key: "SLIDER_CONTROL") {
				attributeState "level", action:"switch level.setLevel"
			}
		}

		standardTile("refresh", "device.refresh", width: 2, height: 2) {
			state "default", label:'Refresh', action:"refresh.refresh", icon:""
		}

		main "switch"
		details(["switch", "refresh"])
	}
}

def updated() {
	if (!state.configured) {		
		return response(configure())
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
	def result = []
	def evt = zigbee.getEvent(description)
	if (evt) {
		if (state.polling) {
			logDebug "Poll Successful"
			state.polling = false
		}
		result << createEvent(evt)
	}
	else {
		def map = zigbee.parseDescriptionAsMap(description)
		if (map) {
			result += handleUnknownDescriptionMap(map)
		}
		else { 
			logDebug "Unknown Command: $description"
		}
	}
	return result
}

private handleUnknownDescriptionMap(map) {
	def result = []
	if ("${map.command}" == "0A") {	
	
		if (map.clusterInt == 6) {
			logDebug "Switch Reported"
			result += response(getSwitchValue())					
		}
		else if (map.clusterInt == 8) {
			logDebug "Switch Level Reported"
			result += response(getSwitchLevelValue())
		}
		
		result << createEvent(getLastPollEventMap())
	}
	return result
}

private getLastPollEventMap() {
	[
		name: "lastPoll", 
		value: new Date().time, 
		displayed: false, 
		isStateChange: true
	]
}

def poll() {	
	if (autoPollStopped()) {
		logDebug "Starting Poll"
		state.polling = true
		runIn(10, checkPoll)
		return getSwitchValue() +
			getSwitchLevelValue() +
			configureSwitchReporting() +
			configureSwitchLevelReporting()
	}
}

// Device self polls/reports, but if the bulb is turned off
// the reporting stops so 
private autoPollStopped() {
	def lastPoll = device.currentValue("lastPoll")
	if (!lastPoll) {
		return true
	}
	else {
		def problemFrequencyMS = ((maxSelfPollFrequencySeconds() + 60) * 1000)
		return (lastPoll < (new Date().time - problemFrequencyMS))
	}	
}

void checkPoll() {
	if (state.polling) {
		state.polling = false
		log.warn "Poll Failed"
	}
}

def setLevel(value) {
	value = validateLevel(value)
	logDebug "Changing Level to $value"	
	[
		zigbee.setLevel(value, 0),
		"delay 100",
		getSwitchLevelValue()
	]
}

private Integer validateLevel(value) {
	Integer result = 100
	try {
		if (value != null && value > 0) {
			result = value.toInteger()
		}
		else if (value == 0) {
			result = 1
		}		
	}
	catch (e) {
		log.error "Unable to validate level ${value} so using 100 instead.\nError: $e"
	}
	return result
}

def off() {
	logDebug "Turning Off"
	zigbee.off()
}

def on() {
	logDebug "Turning On"
	zigbee.on()
}

def refresh() {
	return getSwitchValue() +
		getSwitchLevelValue() +
		configureSwitchReporting() +
		configureSwitchLevelReporting()
}
  
def configure() {
	logDebug "Configuring Reporting and Bindings"	
	state.configured = true
	return configureSwitchReporting() +
		configureSwitchLevelReporting() +
		getSwitchValue() +
		getSwitchLevelValue()
}

private getSwitchValue() {
	zigbee.readAttribute(0x0006, 0x0000)
}

private getSwitchLevelValue() {
	zigbee.readAttribute(0x0008, 0x0000)
}

private configureSwitchReporting() {
	zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, maxSelfPollFrequencySeconds(), null)
}

private configureSwitchLevelReporting() {
	zigbee.configureReporting(0x0008, 0x0000, 0x20, 1, (maxSelfPollFrequencySeconds() + 30), 0x01)
}

private maxSelfPollFrequencySeconds() {
	return 600
}

private logDebug(msg) {
	if (settings.debugOutput) {
		log.debug "$msg"
	}
}
