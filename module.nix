packages:

{ config, lib, pkgs, ... }:

with lib;
let
  mabel = packages."${pkgs.system}".mabel;
  cfg = config.services.mabel;

in {
  options.services.mabel = with types; {
    enable = mkEnableOption "Enable Mabel home monitor server.";

    verbose = mkEnableOption "Generate verbose logs & output.";

    mqtt = {
      host = mkOption {
        type = str;
        description = "Hostname of the MQTT server.";
      };

      port = mkOption {
        type = port;
        description = "Port on which the MQTT server is listening.";
        default = 1883;
      };

      username = mkOption {
        type = str;
        description = "User as which to connect to the MQTT server.";
      };

      password-file = mkOption {
        type = str;
        description =
          "File (on the local host) containing the password for the MQTT server.";
      };
    };

    matrix = {
      domain = mkOption {
        type = str;
        description = "Domain name of the MQTT server.";
      };

      username = mkOption {
        type = str;
        description = "User as which to connect to the MQTT server.";
      };

      password-file = mkOption {
        type = str;
        description =
          "File (on the local host) containing the password for the MQTT server.";
      };

      channel-alias = mkOption {
        type = str;
        description = "Matrix channel to which Mabel should post updates.";
      };
    };
  };

  config = mkIf cfg.enable {
    systemd.services.mabel-server = {
      path = [ mabel ];
      wantedBy = [ "multi-user.target" ];
      after = [ "network-online.target" ];
      serviceConfig = {
        DynamicUser = true;
        Restart = "on-failure";
        RestartSec = "120s";
        LoadCredential = [
          "mqtt.passwd:${cfg.mqtt.password-file}"
          "matrix.passwd:${cfg.matrix.password-file}"
        ];
        ExecStart = pkgs.writeShellScript "mabel-server.sh"
          (concatStringsSep " " ([
            "mabel"
            "--mqtt-host=${cfg.mqtt.host}"
            "--mqtt-port=${toString cfg.mqtt.port}"
            "--mqtt-user=${cfg.mqtt.username}"
            "--mqtt-password-file=${cfg.mqtt.password-file}"
            "--matrix-domain=${cfg.matrix.domain}"
            "--matrix-user=${cfg.matrix.username}"
            "--matrix-password-file=${cfg.matrix.password-file}"
            "--matrix-room=${cfg.matrix.channel-alias}"
          ]));
        unitConfig.ConditionPathExists =
          [ cfg.mqtt.password-file cfg.matrix.password-file ];
      };
    };
  };
}
