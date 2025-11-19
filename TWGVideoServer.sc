TWGVideoServer {
  var <win, <video, <rehcam, <folder, <soundfiles, <loaded = false, <running = false, <legacy_mode = false, <>show_path, <out_buses, <matrix, <matrix_arr, <buses, <businfo, <>preset, <ffspeed;
  var <connectedClients, <connectedClientNames;
  var <erin; // legacy

  *initClass {
    Class.initClassTree(ServerBoot);
    Class.initClassTree(SynthDescLib);

    ServerBoot.add {
      SynthDef(\busPlayer, {
        var state = \state.kr(0); // -1 rw 0 pause 1 play 2 ff
        var ffspeed = \ffspeed.kr(8);
        var out = \out.kr(0);
        var index = \index.kr(0);
        var on = \on.kr(0);
        var buf = \buf.kr(0);
        var cuePos = \cuePos.krBig(0);
        var cueTrig = \cueTrig.tr(0);
        var rate_in = \rate.kr(1);
        var ramp = \ramp.kr(0);
        var curve = \curve.kr(1);
        var amp = \amp.kr(1);
        var rate = VarLag.kr(rate_in, ramp, warp: curve);
        var start = \start.kr(0);
        var end = \end.kr(1);
        var loop = \loop.kr(0);
        var pitch = \pitch.kr(0);
        // make fast forward less grating on ears
        var lpf_freq = rate.abs.linlin(1, 3, 20000, 5000);
        var amp_adjust = rate.abs.linlin(1, 3, 0, -12).dbamp * InRange.kr(state, 1, 1);
        var sig, playhead, isPlaying;
        // modify playback rate for current play state
        var cur_rate = Select.kr(state + 1, [rate.abs.neg * ffspeed, DC.kr(0), rate, rate.abs * ffspeed]);
        var paused = InRange.kr(state, 0, 0);
        var is_ramping = Trig.kr(Changed.kr(ramp + rate_in), ramp);
        var replyTrig;
        start = (start * BufFrames.kr(buf)) -1; //move loop points one sample outside as a precaution
        end = (end * BufFrames.kr(buf)) +1;
        #sig, playhead, isPlaying = SuperPlayBufX.arDetails(2, buf, cur_rate * on, cueTrig, cuePos, start, end, loop);
        sig = if (pitch, PitchShift.ar(sig, pitchRatio: 1/rate.abs), sig);
        sig = sig * on;
        replyTrig = if (paused, Changed.ar(playhead.asArray[0]), Impulse.ar(60) * on);
        SendReply.ar(replyTrig, '/playhead', playhead.asArray, index); // if paused, update playhead only when changed
        SendReply.kr(Impulse.kr(10) * on * is_ramping + (Changed.kr(rate) * (1 - is_ramping)), '/rate', rate, index); // update rate only if the input rate changes or if in the middle of a ramp
        Out.ar(out, LeakDC.ar(LPF.ar(sig, lpf_freq)) * amp * amp_adjust);
      }).add;
    };
  }


  *new { |showspath = "~/Desktop/Shows", legacy = false|
    ^super.new.init(showspath, legacy);
  }

  init { |showspath, legacy|
    legacy_mode = legacy;
    folder = PathName(showspath.standardizePath);
    soundfiles = ();
    matrix_arr = 0!5!6;
    connectedClients = [];
    connectedClientNames = [];
    ffspeed = 8;
    preset = 1;
    video = NetAddr("localhost", 10000);
    rehcam = NetAddr("192.168.2.200", 10000);
    // legacy
    erin = NetAddr("192.168.2.2", 7400);

    OSCdef(\server_ping, { |msg, time, addr, recvPort|
      var name = msg[1];
      var clientIndex = connectedClientNames.indexOf(name);
      if (clientIndex.notNil) {
        connectedClients[clientIndex] = addr;
        this.updateClient(addr);
      } {
        connectedClients = connectedClients.add(addr);
        connectedClientNames = connectedClientNames.add(name);
        win.updateClientsText(connectedClientNames);
        this.updateClient(addr);
      };
    }, '/ping');

    OSCdef(\server_quit, { |msg, time, addr, recvPort|
      var name = msg[1];
      var clientIndex = connectedClientNames.indexOf(name);
      if (clientIndex.notNil) {
        connectedClients.removeAt(clientIndex);
        connectedClientNames.removeAt(clientIndex);
        win.updateClientsText(connectedClientNames);
      } {

      };
    }, '/quit');

    OSCdef(\timeline_query, {|msg, time, addr, recvPort|
      var data = ([[0, \blank, 5.0]] ++ soundfiles.collect({ |sf, index| [index, sf[\name].asSymbol, sf[\buf].duration] })).sort({ |arr1, arr2| arr1[0] < arr2[0] }).flat;
      //data.postln;
      addr.sendMsg("/timeline_info", *data);
    }, "/timeline_query");
  }

  boot { |onCompleteFunction|
    var s = Server.local;

    s.options.sampleRate = 48000;
    s.options.outDevice = "ASIO : Dante Virtual Soundcard (x64)";
    s.options.inDevice = "ASIO : Dante Virtual Soundcard (x64)";
    s.options.numOutputBusChannels = 5;

    Server.killAll;

    s.waitForBoot {
      this.makeGUI;
      out_buses = 5.collect { Bus.audio(s, 2) };
      onCompleteFunction.value;
    };
  }

  makeGUI {
    defer {
      win = TWGVideoServerWindow(this);
    }
  }

  start_audio {
    running = true;

    matrix = {
      var sigs = out_buses.collect { |bus, i|
        var in = In.ar(bus, 2);
        SendPeakRMS.ar(in[0], 10, 3, '/db', i * 2);
        SendPeakRMS.ar(in[1], 10, 3, '/db', i * 2 + 1);
        in;
      };
      var sigsLeft = sigs.collect { |sig| sig[0] };
      var sigsRight = sigs.collect { |sig| sig[1] };
      var ear1, ear2, ear3, room, xtra;
      var routing = \routing.kr(0!5!6);
      ear1 = sigsLeft * routing[0];
      ear2 = sigsLeft * routing[1];
      ear3 = sigsLeft * routing[2];
      room = sigsRight * routing[3];
      xtra = sigsRight * routing[4];
      [ear1, ear2, ear3, room, xtra];
    }.play;

    buses = 5.collect { |i|
      (
        buffer: nil,
        instrument: \busPlayer,
        index: i,
        out: out_buses[i],
      ).play;
    };

    businfo = 5.collect { |i|
      (
        media: 0,
        position: 0,
        speed: 1,
        transport: 0,
        db: 0,
        loop: 0,
        loopstart: 0,
        loopend: 100
      )
    };

    this.oscdefs;
  }

  oscdefs {
    OSCdef(\db, { |msg|
      var busNum = floor(msg[2] / 2);
      var bus = (busNum + 65).asInteger.asAscii;
      var sideNum = (msg[2] % 2).asInteger;
      var side = ["l", "r"][sideNum];
      var peakLevel = msg[3];
      var rmsLevel = msg[4];
      defer {
        win.bus_levels[busNum][sideNum].peakLevel = peakLevel.ampdb.linlin(-80, 0, 0, 1, \min);
        win.bus_levels[busNum][sideNum].value = rmsLevel.ampdb.linlin(-80, 0, 0, 1);
      };
      connectedClients.do(_.sendMsg('/fromvideo', \level, busNum, sideNum, peakLevel, rmsLevel));
      // legacy
      if (legacy_mode) {
        erin.sendMsg(("/db/" ++ bus ++ "/" ++ side).asSymbol, rmsLevel.ampdb.max(-90));
      };
    }, '/db');

    OSCdef(\playhead, { |msg|
      var index = msg[2].asInteger;
      var buf = buses[index].buffer;
      if (buf.notNil) {
        var pos = ( buf.atPair(msg[3], msg[4]) / buf.duration ) * 100;
        var letter = (65 + index).asAscii;
        businfo[index][\position] = pos;
        //if (index < 3) {
		video.sendMsg(("pos_" ++ letter).asSymbol, pos);
		rehcam.sendMsg(("pos_" ++ letter).asSymbol, pos);
        //};
        connectedClients.do(_.sendMsg('/fromvideo', \pos, index, pos));
        // legacy
        if (legacy_mode) {
          erin.sendMsg(("/pos/" ++ letter).asSymbol, pos)
        };
      };
    }, '/playhead');

    OSCdef(\rate, { |msg|
      var index = msg[2].asInteger;
      var buf = buses[index].buffer;
      if (buf.notNil) {
        var rate = msg[3];
        var letter = (65 + index).asAscii;
        businfo[index][\speed] = rate;
        connectedClients.do(_.sendMsg('/fromvideo', \speed, index, rate));
      }
    }, '/rate');

    OSCdef(\fromsm, { |msg|
      var media, pos, speed, zoom, state, db, loop;
      var rate, ramp, curve, pitch;
      var loopOn, loopStart, loopEnd;
      var osc_buses = msg[1..35].clump(7);
      var matrix = msg[41];

      osc_buses.do { |bus, i|
        # media, pos, speed, zoom, state, db, loop = bus;
        if (media != 'n' && media.notNil) {
          media = media.asInteger;
          businfo[i][\media] = media;
          connectedClients.do(_.sendMsg('/fromvideo', \media, i, media));
          //if (i < 3) {
          video.sendMsg(("media_" ++ (65 + i).asAscii).asSymbol, media);
          rehcam.sendMsg(("media_" ++ (65 + i).asAscii).asSymbol, media);
          //};
          if (media == 0) {
            buses[i].set(\on, 0);
            buses[i].buffer = nil;
          } {
            buses[i].set(\buf, soundfiles[media].buf, \on, 1);
            buses[i].buffer = soundfiles[media].buf;
          };
        };
        if (pos != 'n' && pos.notNil) {
          var buf = buses[i].buffer;
          if (businfo[i][\media] == 0) {
            businfo[i][\position] = pos.asFloat;
            connectedClients.do(_.sendMsg('/fromvideo', \pos, i, pos.asFloat));
          };
          if (buf.notNil) {
            buses[i].set(\cuePos, buf.atSec(pos.asFloat * 0.01 * buf.duration), \cueTrig, 1)
          };
        };
        if (speed != 'n' && speed.notNil) {
          # rate, ramp, curve, pitch = speed.asString.split($ ).asFloat;
          if (businfo[i][\media] == 0) {
            businfo[i][\speed] = rate;
            connectedClients.do(_.sendMsg('/fromvideo', \speed, i, rate));
          };
          buses[i].set(\curve, curve ? 3, \ramp, ramp, \rate, rate, \pitch, pitch);
          video.sendMsg(("rate_" ++ (65 + i).asAscii).asSymbol, rate);
        };
        if (loop != 'n' && loop.notNil) {
          # loopOn, loopStart, loopEnd = loop.asString.split($ ).asFloat;
          if (loopOn.asBoolean.not) {
            buses[i].set(\start, 0, \end, 1, \loop, 0); // if looping is off, move start and end to beginning and end
          } {
            var curPos =  businfo[i][\position];
            if ( (loopStart > curPos) || (loopEnd < curPos) ) {
              var buf = buses[i].buffer;
              buses[i].set(\cuePos, buf.atSec(loopStart.asFloat * 0.01 * buf.duration), \cueTrig, 1)
            }; // move playhead to loop start if not already in loop area
            buses[i].set(\start, loopStart * 0.01 ? 0, \end, loopEnd * 0.01 ? 1, \loop, 1);
          };

          businfo[i][\loop] = loopOn.asBoolean;
          businfo[i][\loopstart] = loopStart;
          businfo[i][\loopend] = loopEnd;

          connectedClients.do(_.sendMsg('/fromvideo', \loop, i, businfo[i][\loop], businfo[i][\loopstart], businfo[i][\loopend]));
          //here update businfo and send /fromvideo message to connectedClients
        };
        if (zoom != 'n' && zoom.notNil) {
          if (i < 3) {
            video.sendMsg(("zoom_" ++ (65 + i).asAscii).asSymbol, zoom);
          };
          if (i == 2) {
            ffspeed = zoom.asInteger;
            buses.do(_.set(\ffspeed, ffspeed));
            connectedClients.do(_.sendMsg('/fromvideo', \ffspeed, ffspeed));
          };
          if (i == 3) {
            preset = zoom.asInteger;
						defer {win.presetNum.value_(preset)};
            video.sendMsg(\preset, preset);
            connectedClients.do(_.sendMsg('/fromvideo', \preset, preset));
          };
          if (i == 4) {
            video.sendMsg(\preset_trigger, 1);
          };
        };
        if (state != 'n' && state.notNil && legacy_mode.not) { // in legacy mode, transport state messages are suppressed
          buses[i].set(\state, state.asFloat);
          businfo[i][\transport] = state;
          connectedClients.do(_.sendMsg('/fromvideo', \transport, i, state));
		video.sendMsg(("transport_" ++ (65 + i).asAscii).asSymbol, state);
        };
        if (db != 'n' && db.notNil) {
          buses[i].set(\amp, db.asFloat.dbamp);
          businfo[i][\db] = db.asFloat;
          connectedClients.do(_.sendMsg('/fromvideo', \db, i, db.asFloat))
        }
      };
      if (matrix.notNil) {
        var arr = 0!5!6;
        matrix.asString.split($ ).asInteger.clump(3).do { |cell|
          var col = cell[0];
          var row = cell[1];
          var val = cell[2];
          arr[row][col] = val;
        };
        this.set_matrix(arr);
      };
    }, '/fromsm');

    fork {
      Server.local.sync;
      this.set_matrix(matrix_arr);
    };

    win.audio_running_label.string_("Audio is on.");
  }

  set_matrix { |arr|
    var matrixMsg = [];
    arr.do { |subarr, row|
      subarr.do { |val, col|
        defer { win.matrix_butts[row][col].value_(val.asBoolean) };
        matrixMsg = matrixMsg.addAll([col, row, val]);
        // legacy
        if (legacy_mode, {erin.sendMsg('/matrix', col, row, val)});
      };
    };
    connectedClients.do(_.sendMsg('/fromvideo', \routing, *matrixMsg));
    matrix.set(\routing, arr);
    matrix_arr = arr;
  }

  set_matrix_action { |row, col, val|
    matrix_arr[row][col] = val;
    connectedClients.do(_.sendMsg('/fromvideo', \route, col, row, val));
    //legacy
    if (legacy_mode, {erin.sendMsg('/matrix', col, row, val)});
    matrix.set(\routing, matrix_arr);
  }

  stop_audio {
    win.audio_running_label.string_("Audio is off.");
    running = false;
    buses.do(_.free);
    matrix.free;
    win.bus_levels.do { |arr, i|
      var bus = (i + 65).asAscii;
      arr.do { |meter, j|
        var side = ["l", "r"][j];
        meter.peakLevel = 0;
        meter.value = 0;
        connectedClients.do(_.sendMsg(("/db/" ++ bus ++ "/" ++ side).asSymbol, -90));
        if (legacy_mode, erin.sendMsg(("/db/" ++ bus ++ "/" ++ side).asSymbol, -90));
      };
    };
  }

  load_show {
    video.sendMsg('/load', show_path.folderName);
    win.loaded_label.string_("Loading " ++ show_path.folderName ++ "...");
    this.stop_audio;
    win.start_butt.visible_(false);
    win.stop_butt.visible_(false);
    this.load_files({
      win.loaded_label.string_("Currently loaded " ++ show_path.folderName);
      win.start_butt.visible_(true);
      win.stop_butt.visible_(true);
    });
  }

  load_files { |action|
    var s = Server.local;
    fork {
      var pathname = PathName(show_path.fullPath +/+ "Media/Audio/"); //
      soundfiles.do { |sf| sf.buf.free; };
      s.sync;
      soundfiles = ();
      pathname.files.do { |pathname|
        if (pathname.fileName == ".DS_Store" || pathname.fileName[(pathname.fileName.size-4)..] == ".asd") {
          // skip
        } {
          var filename = pathname.fileNameWithoutExtension;
          var filenum = filename[0..3].asInteger;
          filename.postln;
          soundfiles[filenum] = ();
          soundfiles[filenum][\name] = filename[3..];
          soundfiles[filenum][\soundfile] = SoundFile(pathname.fullPath);
          soundfiles[filenum][\buf] = Buffer.read(s, pathname.fullPath);
          s.sync;
        };
      };
      defer { action.();
        loaded = true;
        connectedClients.do({ |client| this.updateClient(client) });
      };
    };
  }

  cleanup {
    soundfiles.do { |sf| sf.buf.free; };
  }

  updateClient { |addr|
    if (loaded) {
      var msg;
      msg = ['/fromvideo', \showinfo, show_path.folderName] ++ soundfiles.collect({|item, i| i.asString + item[\name]});
      addr.sendMsg(*msg);

      businfo.do({ |bus, i|
        addr.sendMsg('/fromvideo', \media, i, businfo[i][\media]);
        addr.sendMsg('/fromvideo', \pos, i, businfo[i][\position]);
        addr.sendMsg('/fromvideo', \speed, i, businfo[i][\speed]);
        addr.sendMsg('/fromvideo', \db, i, businfo[i][\db]);
        addr.sendMsg('/fromvideo', \transport, i, businfo[i][\transport]);
        addr.sendMsg('/fromvideo', \loop, i, businfo[i][\loop], businfo[i][\loopstart], businfo[i][\loopend]);
      });

      addr.sendMsg('/fromvideo', \preset, preset);
      addr.sendMsg('/fromvideo', \ffspeed, ffspeed);


      msg = ['/fromvideo', \routing];
      matrix_arr.do { |subarr, row|
        subarr.do { |val, col|
          msg = msg.addAll([col, row, val]);
        }
      };
      addr.sendMsg(*msg);
    } {
      addr.sendMsg('/fromvideo')
    };
  }

}


