TWGVideoServerWindow : SCViewHolder {
  var <win, <menu, <loaded_label, <audio_running_label, <load_butt, <start_butt, <stop_butt, <meter, <bus_levels, <recompile_butt, <matrix_butts, <connectedClientsText, <presetNum, <presetRetriggerBut;
  var <model;

  *new { |model|
    ^super.new.init(model);
  }

  init { |argmodel|
    model = argmodel;

    win = Window("Audio control", Rect(5,250,400,500))
    .onClose_({ model.stop_audio; model.cleanup; }).front;

    menu = EZPopUpMenu(win, Rect(10, 5, 250, 22), " Show: ");

    loaded_label = StaticText(win, Rect(50, 30, 300, 30)).string_("Nothing loaded yet.");
    audio_running_label = StaticText(win, Rect(50, 52, 300, 30)).string_("Audio is off.");

    load_butt = Button(win, Rect(270, 5, 80, 22)).states_([["Load"]]).action_({ model.load_show });
    start_butt = Button(win, Rect(270, 30, 120, 22)).states_([["Start audio"]]).action_({ model.start_audio }).visible_(false);
    stop_butt = Button(win, Rect(270, 55, 120, 22)).states_([["Stop audio"]]).action_({ model.stop_audio }).visible_(false);

    meter = ServerMeterView(Server.local, win, 10@80, 0, 5);
    StaticText(win, Rect(150, 85, 100, 15))
    .font_(Font.sansSerif(10).boldVariant)
    .string_("Buses");

    bus_levels = Array.fill(5, { |i|
      Array.fill(2, { |j|
        StaticText(win, Rect(162 + (i * 50), 285, 20, 15))
        .font_(Font.sansSerif(9).boldVariant)
        .string_((65 + i).asAscii);
        LevelIndicator(win, Rect(150 + (i * 50) + (j * 20), 105, 15, 180) ).warning_(0.9).critical_(1.0)
        .drawsPeak_(true)
        .numTicks_(9)
        .numMajorTicks_(3)
      });
    });

    recompile_butt = Button(win, Rect(270, 305, 120, 22)).states_([["Recompile SC"]]).action_({ thisProcess.recompile });
    matrix_butts = Array.fill(6, { |row|
      Array.fill(5, { |col|
        CheckBox(win, Rect(35 + (col * 23), 325 + (row * 23), 20, 20)).action_({ |checkbox|
          model.set_matrix_action(row, col, if (checkbox.value) {1} {0})
        })
      })
    });
    5.do { |busno|
      StaticText(win, Rect(38 + (busno * 23), 310, 15, 15)).font_(Font.sansSerif(9).boldVariant).string_((busno + 65).asAscii);
    };
    ["Ear 1", "Ear 2", "Ear 3", "Room", "Xtra", "(Phones)"].do { |name, i|
      StaticText(win, Rect(147, 327 + (i * 23), 100, 15)).font_(Font.sansSerif(9).boldVariant).string_("% - %".format(i, name));
    };

    model.folder.folders.do { |pathname|
      var name = pathname.folderName;
      menu.addItem(name.asSymbol, { model.show_path = pathname });
    };
    menu.valueAction = 0;

    connectedClientsText = StaticText(win, Rect(270, 350, 200, 300)).align_(\topLeft).string_("Listening on port" + NetAddr.localAddr.port.asString++$\n++"Connected Clients:");

    StaticText(win, Rect(270, 430, 120, 20)).string_("Video Preset:");
    presetNum = NumberBox(win, Rect(348, 430, 40, 20)).value_(1)
    .action_({|x|
      model.preset = x.value;
      model.video.sendMsg(\preset, x.value);
      model.connectedClients.do(_.sendMsg('/fromvideo', \preset, x.value));
    });
    presetRetriggerBut = Button(win, Rect(270, 455, 120, 22)).string_("Retrigger")
    .action_({
      model.video.sendMsg(\preset_trigger, 1)
    });
  }

  updateClientsText { |clients|
    var str = "Listening on port" + NetAddr.localAddr.port.asString++$\n++"Connected Clients:";
    clients.do({|item|
      str = str ++ $\n ++ item;
    });
    {
      connectedClientsText.string_(str);
    }.defer;
  }
}
