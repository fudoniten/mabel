{
  description = "Mabel Home Bot";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-24.11";
    utils.url = "github:numtide/flake-utils";
    helpers = {
      url = "github:fudoniten/fudo-nix-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    mebot = {
      url = "github:fudoniten/mebot";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    milquetoast = {
      url = "github:fudoniten/milquetoast";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = { self, nixpkgs, utils, helpers, milquetoast, mebot, ... }:
    utils.lib.eachDefaultSystem (system:
      let
        inherit (helpers.packages."${system}") mkClojureBin;
        pkgs = nixpkgs.legacyPackages."${system}";
        cljLibs = {
          "org.fudo/mebot" = "${mebot.packages."${system}".mebot}";
          "org.fudo/milquetoast" =
            "${milquetoast.packages."${system}".milquetoast}";
        };
      in {
        packages = rec {
          default = mabel;
          mabel = mkClojureBin {
            name = "org.fudo/mabel";
            primaryNamespace = "mabel.cli";
            src = ./.;
            inherit cljLibs;
          };
        };

        devShells = rec {
          default = updateDeps;
          updateDeps = pkgs.mkShell {
            buildInputs = with helpers.packages."${system}";
              [ (updateClojureDeps cljLibs) ];
          };
          mabel =
            pkgs.mkShell { buildInputs = [ self.packages."${system}".mabel ]; };
        };
      }) // {
        nixosModules = rec {
          default = mabel;
          mabel = import ./module.nix self.packages;
        };
      };
}
