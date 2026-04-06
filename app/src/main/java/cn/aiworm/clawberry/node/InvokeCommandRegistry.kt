package clawberry.aiworm.cn.node

import clawberry.aiworm.cn.protocol.OpenClawCalendarCommand
import clawberry.aiworm.cn.protocol.OpenClawCanvasA2UICommand
import clawberry.aiworm.cn.protocol.OpenClawCanvasCommand
import clawberry.aiworm.cn.protocol.OpenClawCameraCommand
import clawberry.aiworm.cn.protocol.OpenClawCapability
import clawberry.aiworm.cn.protocol.OpenClawCallLogCommand
import clawberry.aiworm.cn.protocol.OpenClawContactsCommand
import clawberry.aiworm.cn.protocol.OpenClawDeviceCommand
import clawberry.aiworm.cn.protocol.OpenClawLocationCommand
import clawberry.aiworm.cn.protocol.OpenClawMotionCommand
import clawberry.aiworm.cn.protocol.OpenClawNotificationsCommand
import clawberry.aiworm.cn.protocol.OpenClawPhotosCommand
import clawberry.aiworm.cn.protocol.OpenClawSmsCommand
import clawberry.aiworm.cn.protocol.OpenClawSystemCommand

data class NodeRuntimeFlags(
  val cameraEnabled: Boolean,
  val locationEnabled: Boolean,
  val sendSmsAvailable: Boolean,
  val readSmsAvailable: Boolean,
  val voiceWakeEnabled: Boolean,
  val motionActivityAvailable: Boolean,
  val motionPedometerAvailable: Boolean,
  val debugBuild: Boolean,
)

enum class InvokeCommandAvailability {
  Always,
  CameraEnabled,
  LocationEnabled,
  SendSmsAvailable,
  ReadSmsAvailable,
  MotionActivityAvailable,
  MotionPedometerAvailable,
  DebugBuild,
}

enum class NodeCapabilityAvailability {
  Always,
  CameraEnabled,
  LocationEnabled,
  SmsAvailable,
  VoiceWakeEnabled,
  MotionAvailable,
}

data class NodeCapabilitySpec(
  val name: String,
  val availability: NodeCapabilityAvailability = NodeCapabilityAvailability.Always,
)

data class InvokeCommandSpec(
  val name: String,
  val requiresForeground: Boolean = false,
  val availability: InvokeCommandAvailability = InvokeCommandAvailability.Always,
)

object InvokeCommandRegistry {
  val capabilityManifest: List<NodeCapabilitySpec> =
    listOf(
      NodeCapabilitySpec(name = OpenClawCapability.Canvas.rawValue),
      NodeCapabilitySpec(name = OpenClawCapability.Device.rawValue),
      NodeCapabilitySpec(name = OpenClawCapability.Notifications.rawValue),
      NodeCapabilitySpec(name = OpenClawCapability.System.rawValue),
      NodeCapabilitySpec(
        name = OpenClawCapability.Camera.rawValue,
        availability = NodeCapabilityAvailability.CameraEnabled,
      ),
      NodeCapabilitySpec(
        name = OpenClawCapability.Sms.rawValue,
        availability = NodeCapabilityAvailability.SmsAvailable,
      ),
      NodeCapabilitySpec(
        name = OpenClawCapability.VoiceWake.rawValue,
        availability = NodeCapabilityAvailability.VoiceWakeEnabled,
      ),
      NodeCapabilitySpec(
        name = OpenClawCapability.Location.rawValue,
        availability = NodeCapabilityAvailability.LocationEnabled,
      ),
      NodeCapabilitySpec(name = OpenClawCapability.Photos.rawValue),
      NodeCapabilitySpec(name = OpenClawCapability.Contacts.rawValue),
      NodeCapabilitySpec(name = OpenClawCapability.Calendar.rawValue),
      NodeCapabilitySpec(
        name = OpenClawCapability.Motion.rawValue,
        availability = NodeCapabilityAvailability.MotionAvailable,
      ),
      NodeCapabilitySpec(name = OpenClawCapability.CallLog.rawValue),
    )

  val all: List<InvokeCommandSpec> =
    listOf(
      InvokeCommandSpec(
        name = OpenClawCanvasCommand.Present.rawValue,
        requiresForeground = true,
      ),
      InvokeCommandSpec(
        name = OpenClawCanvasCommand.Hide.rawValue,
        requiresForeground = true,
      ),
      InvokeCommandSpec(
        name = OpenClawCanvasCommand.Navigate.rawValue,
        requiresForeground = true,
      ),
      InvokeCommandSpec(
        name = OpenClawCanvasCommand.Eval.rawValue,
        requiresForeground = true,
      ),
      InvokeCommandSpec(
        name = OpenClawCanvasCommand.Snapshot.rawValue,
        requiresForeground = true,
      ),
      InvokeCommandSpec(
        name = OpenClawCanvasA2UICommand.Push.rawValue,
        requiresForeground = true,
      ),
      InvokeCommandSpec(
        name = OpenClawCanvasA2UICommand.PushJSONL.rawValue,
        requiresForeground = true,
      ),
      InvokeCommandSpec(
        name = OpenClawCanvasA2UICommand.Reset.rawValue,
        requiresForeground = true,
      ),
      InvokeCommandSpec(
        name = OpenClawSystemCommand.Notify.rawValue,
      ),
      InvokeCommandSpec(
        name = OpenClawCameraCommand.List.rawValue,
        requiresForeground = true,
        availability = InvokeCommandAvailability.CameraEnabled,
      ),
      InvokeCommandSpec(
        name = OpenClawCameraCommand.Snap.rawValue,
        requiresForeground = true,
        availability = InvokeCommandAvailability.CameraEnabled,
      ),
      InvokeCommandSpec(
        name = OpenClawCameraCommand.Clip.rawValue,
        requiresForeground = true,
        availability = InvokeCommandAvailability.CameraEnabled,
      ),
      InvokeCommandSpec(
        name = OpenClawLocationCommand.Get.rawValue,
        availability = InvokeCommandAvailability.LocationEnabled,
      ),
      InvokeCommandSpec(
        name = OpenClawDeviceCommand.Status.rawValue,
      ),
      InvokeCommandSpec(
        name = OpenClawDeviceCommand.Info.rawValue,
      ),
      InvokeCommandSpec(
        name = OpenClawDeviceCommand.Permissions.rawValue,
      ),
      InvokeCommandSpec(
        name = OpenClawDeviceCommand.Health.rawValue,
      ),
      InvokeCommandSpec(
        name = OpenClawNotificationsCommand.List.rawValue,
      ),
      InvokeCommandSpec(
        name = OpenClawNotificationsCommand.Actions.rawValue,
      ),
      InvokeCommandSpec(
        name = OpenClawPhotosCommand.Latest.rawValue,
      ),
      InvokeCommandSpec(
        name = OpenClawContactsCommand.Search.rawValue,
      ),
      InvokeCommandSpec(
        name = OpenClawContactsCommand.Add.rawValue,
      ),
      InvokeCommandSpec(
        name = OpenClawCalendarCommand.Events.rawValue,
      ),
      InvokeCommandSpec(
        name = OpenClawCalendarCommand.Add.rawValue,
      ),
      InvokeCommandSpec(
        name = OpenClawMotionCommand.Activity.rawValue,
        availability = InvokeCommandAvailability.MotionActivityAvailable,
      ),
      InvokeCommandSpec(
        name = OpenClawMotionCommand.Pedometer.rawValue,
        availability = InvokeCommandAvailability.MotionPedometerAvailable,
      ),
      InvokeCommandSpec(
        name = OpenClawSmsCommand.Send.rawValue,
        availability = InvokeCommandAvailability.SendSmsAvailable,
      ),
      InvokeCommandSpec(
        name = OpenClawSmsCommand.Search.rawValue,
        availability = InvokeCommandAvailability.ReadSmsAvailable,
      ),
      InvokeCommandSpec(
        name = OpenClawCallLogCommand.Search.rawValue,
      ),
      InvokeCommandSpec(
        name = "debug.logs",
        availability = InvokeCommandAvailability.DebugBuild,
      ),
      InvokeCommandSpec(
        name = "debug.ed25519",
        availability = InvokeCommandAvailability.DebugBuild,
      ),
    )

  private val byNameInternal: Map<String, InvokeCommandSpec> = all.associateBy { it.name }

  fun find(command: String): InvokeCommandSpec? = byNameInternal[command]

  fun advertisedCapabilities(flags: NodeRuntimeFlags): List<String> {
    return capabilityManifest
      .filter { spec ->
        when (spec.availability) {
          NodeCapabilityAvailability.Always -> true
          NodeCapabilityAvailability.CameraEnabled -> flags.cameraEnabled
          NodeCapabilityAvailability.LocationEnabled -> flags.locationEnabled
          NodeCapabilityAvailability.SmsAvailable -> flags.sendSmsAvailable || flags.readSmsAvailable
          NodeCapabilityAvailability.VoiceWakeEnabled -> flags.voiceWakeEnabled
          NodeCapabilityAvailability.MotionAvailable -> flags.motionActivityAvailable || flags.motionPedometerAvailable
        }
      }
      .map { it.name }
  }

  fun advertisedCommands(flags: NodeRuntimeFlags): List<String> {
    return all
      .filter { spec ->
        when (spec.availability) {
          InvokeCommandAvailability.Always -> true
          InvokeCommandAvailability.CameraEnabled -> flags.cameraEnabled
          InvokeCommandAvailability.LocationEnabled -> flags.locationEnabled
          InvokeCommandAvailability.SendSmsAvailable -> flags.sendSmsAvailable
          InvokeCommandAvailability.ReadSmsAvailable -> flags.readSmsAvailable
          InvokeCommandAvailability.MotionActivityAvailable -> flags.motionActivityAvailable
          InvokeCommandAvailability.MotionPedometerAvailable -> flags.motionPedometerAvailable
          InvokeCommandAvailability.DebugBuild -> flags.debugBuild
        }
      }
      .map { it.name }
  }
}
