{
  description = "Mabel Home Bot";

  inputs = {
    nixpkgs.url = "nixpkgs/nixos-23.11";
    utils.url = "github:numtide/flake-utils";
    helpers = {
      url = "git+https://fudo.dev/public/nix-helpers.git";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    mebot = {
      url = "git+https://fudo.dev/public/mebot.git";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    milquetoast = {
      url = "git+https://fudo.dev/public/milquetoast.git";
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
