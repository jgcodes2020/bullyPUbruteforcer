package io.github.jgcodes.bitfs0x;

import com.sun.jna.Pointer;
import io.github.jgcodes.bitfs0x.output.DBOutput;
import io.github.jgcodes.bitfs0x.output.Output;
import io.github.jgcodes.bitfs0x.output.PrintStreamOutput;
import io.github.jgcodes.bitfs0x.util.FloatVector3;
import io.github.jgcodes.libsm64.Game;
import io.github.jgcodes.libsm64.Game.Version;
import io.github.jgcodes.libsm64.Input;
import io.github.jgcodes.libsm64.M64;
import io.github.jgcodes.libsm64.Savestate;
import io.github.jgcodes.libsm64.util.Pointers;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import static io.github.jgcodes.bitfs0x.util.FloatVector3.fvec3;

@Command(
  name = "bully-bruteforcer",
  description = """
    A program which bruteforces sending a bully to space and back so it can hit Mario into a platform.
    It's pretty hard to figure out manually, that's why we're resorting to this.
    """,
  version = """
    BitFS Bully PU Bruteforcer v0.1-alpha
    Author: jgcodes2020 (superminerJG)
    
    For help info, do bully-bruteforcer --help
    """
)
public class BullyBruteforcer implements Callable<Void> {
  // Constants
  private static final FloatVector3 startBullyPos = fvec3(-2236, -2950, -566);
  private static final FloatVector3 hackMarioPos = fvec3(-1945, -2918, -715);
  private static final FloatVector3 targetPos = fvec3(-1720, -2910, -460);

  // Command-line args for Picocli
  @Option(names = {"--help", "-h", "-?"}, description = "Display this help list", usageHelp = true)
  boolean sendHelpMsg;

  @Option(names = {"--version", "-v"}, description = "Show the version help", versionHelp = true)
  boolean sendVersionMsg;

  @Option(names = {"--wafel-path", "-wp"}, description = "Path to wafel DLL", required = true)
  Path dllPath;

  @Option(
    names = {"--db-url", "-url"},
    description = "URL of the BitFSQL database (not trademarked)",
    required = true
  )
  String dbURL;

  @Option(
    names = {"--db-password", "-pw"},
    description = "Password to the BitFSQL database (not trademarked)",
    required = true
  )
  String password;

  @Option(names = "--min-dist", description = "how close to the original position the bully can be", defaultValue = "200")
  float minDist = 0;
  @Option(names = "--max-dist", description = "how far out from the original position the bully can be", defaultValue = "1000")
  float maxDist = 300;

  @Option(names="--min-speed", description = "the minimum speed to check", defaultValue = "2000000")
  float minSpeed = 2000000;
  @Option(names="--max-speed", description = "the maximum speed to check", defaultValue = "10000000")
  float maxSpeed = 10000000;

  @Option(names="--max-frames", description = "the maximum number of frames to simulate before ruling the value out")
  int maxFrames = 26;

  //call method
  @Override
  public Void call() throws Exception {


    // Load SM64 and .m64
    Game game = new Game(Version.JP, dllPath);
    M64 m64 = new M64(BullyBruteforcer.class.getResourceAsStream("/assets/1Key_4_21_13_Padded.m64"));

    // final int backupFrame;

    Savestate st = new Savestate(game);

    // advance to frame 3286
    List<Input> inputs = m64.getInputs();
    for (int frame = 0; frame < inputs.size(); frame++) {
      game.advance(inputs.get(frame));

      if (frame == 3286) {
        final Pointer objPool = game.locate("gObjectPool");
        // Deactivate everything except the bully and the tilting platforms
        for (int obj = 0; obj < 108; obj++) {
          switch (obj) {
            case 27, 83, 84 -> {}
            default -> {
              short activeFlag = objPool.getShort(obj * 1392 + 180);
              objPool.setShort(obj * 1392 + 180, (short) (activeFlag & 0xFFFE));
            }
          }
        }
        st.save();
        // backupFrame = frame + 1;
        break;
      }
    }
    // Initialize all pointers
    final Pointer
      marioX, marioY, marioZ,
      bullyX, bullyY, bullyZ,
      bullyHSpeed, bullyYaw1, bullyYaw2; {
        final Pointer marioPtr = game.locate("gMarioStates");
        marioX = Pointers.incr(marioPtr, 60);
        marioY = Pointers.incr(marioPtr, 64);
        marioZ = Pointers.incr(marioPtr, 68);

        final Pointer bullyPtr = Pointers.incr(game.locate("gObjectPool"), 27 * 1392);

        bullyX = Pointers.incr(bullyPtr, 240);
        bullyY = Pointers.incr(bullyPtr, 244);
        bullyZ = Pointers.incr(bullyPtr, 248);
        bullyHSpeed = Pointers.incr(bullyPtr, 264);
        bullyYaw1 = Pointers.incr(bullyPtr, 280);
        bullyYaw2 = Pointers.incr(bullyPtr, 292);
    }

    // Print bully state to stderr
    st.load();
    System.err.printf("""
        Bully initial state:
        Pos: (%s, %s, %s)
        H speed: %s
        Yaw values: %s, %s
              
              
        """,
      bullyX.getFloat(0),
      bullyY.getFloat(0),
      bullyZ.getFloat(0),
      bullyHSpeed.getFloat(0),
      bullyYaw1.getShort(0) & 0xFFFF,
      bullyYaw2.getShort(0) & 0xFFFF
    );

    // Bruteforce :)
    final Set<FloatVector3> positions = new HashSet<>();
    try (PrintStream fileOut = new PrintStream(new FileOutputStream("results.txt"))) {
      final List<Output> outputs = List.of(
        new PrintStreamOutput(System.out),
        new PrintStreamOutput(fileOut),
        new DBOutput(dbURL, password)
      );

      // Iterate over all valid floats from minSpeed to maxSpeed
      for (int i = Float.floatToIntBits(minSpeed); i < Float.floatToIntBits(maxSpeed); i++) {
        final float bullySpeed = Float.intBitsToFloat(i);
        /*if (i % 1024 == 0)
          System.err.printf("%d -> %f\n", i, bullySpeed);*/

        final long tmstamp = System.currentTimeMillis();
        short bullyYaw = (short) 0;
        do {
          //reset bully pos/angle
          bullyX.setFloat(0, startBullyPos.x());
          bullyY.setFloat(0, startBullyPos.y());
          bullyZ.setFloat(0, startBullyPos.z());

          bullyHSpeed.setFloat(0, bullySpeed);
          bullyYaw1.setShort(0, bullyYaw);
          bullyYaw2.setShort(0, bullyYaw);

          for (int frame = 0; frame < maxFrames; frame++) {
            // hack mario in place and advance
            marioX.setFloat(0, hackMarioPos.x());
            marioY.setFloat(0, hackMarioPos.y());
            marioZ.setFloat(0, hackMarioPos.z());
            game.advance();

            // calculate new bully pos variables
            FloatVector3 newBullyPos = fvec3(
              bullyX.getFloat(0),
              bullyY.getFloat(0),
              bullyZ.getFloat(0)
            );

            // determine horizontal distance to (-1720, -460)
            final double dist = Math.hypot(bullyX.getFloat(0) + 1720d, bullyZ.getFloat(0) + 460d);

            if (dist >= minDist && dist <= maxDist) {
              for (Output output: outputs) {
                output.output(
                  targetPos, frame,
                  startBullyPos, bullySpeed, bullyYaw,
                  newBullyPos, bullyHSpeed.getFloat(0), bullyYaw1.getShort(0)
                );
              }
            }
          }
          bullyYaw++;
        } while (bullyYaw != 0);
        System.err.printf("Iterated over all angles for speed %f in %d ms\n", bullySpeed,
          System.currentTimeMillis() - tmstamp);
      }
    }

    return null;
  }

  public static void main(String[] args) {
    CommandLine cl = new CommandLine(new BullyBruteforcer());
    cl.setExitCodeExceptionMapper(exc -> {
      if (exc instanceof FileNotFoundException) {
        return 2;
      }
      else if (exc instanceof IOException) {
        return 3;
      }
      else if (exc != null) {
        return 1;
      }
      return 0;
    });
    int exitCode = cl.execute(args);
    System.exit(exitCode);
  }
}
