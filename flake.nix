{
  description = "Mabel Home Bot";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-25.11";
    utils.url = "github:numtide/flake-utils";
    helpers = {
      url = "path:/net/projects/niten/nix-helpers";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    mebot = {
      url = "path:/net/projects/niten/mebot";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    milquetoast = {
      url = "path:/net/projects/niten/milquetoast";
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
              [ (updateClojureDeps { deps = cljLibs; }) ];
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
