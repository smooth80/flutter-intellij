/*
 * Copyright 2016 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.sdk;

import com.intellij.execution.process.ProcessOutput;
import com.intellij.ide.actions.ShowSettingsUtilImpl;
import com.intellij.ide.browsers.BrowserLauncher;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.FixedSizeButton;
import com.intellij.openapi.ui.TextComponentAccessor;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ComboboxWithBrowseButton;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.util.PlatformIcons;
import icons.FlutterIcons;
import io.flutter.*;
import io.flutter.bazel.Workspace;
import io.flutter.bazel.WorkspaceCache;
import io.flutter.font.FontPreviewProcessor;
import io.flutter.settings.FlutterSettings;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.text.JTextComponent;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.net.URISyntaxException;

// Note: when updating the settings here, update FlutterSearchableOptionContributor as well.

public class FlutterSettingsConfigurable implements SearchableConfigurable {

  public static final String FLUTTER_SETTINGS_PAGE_NAME = FlutterBundle.message("flutter.title");
  private static final String FLUTTER_SETTINGS_HELP_TOPIC = "flutter.settings.help";

  private JPanel mainPanel;
  private ComboboxWithBrowseButton mySdkCombo;
  private JBLabel myVersionLabel;
  private JCheckBox myReportUsageInformationCheckBox;
  private LinkLabel<?> myPrivacyPolicy;
  private JCheckBox myHotReloadOnSaveCheckBox;
  private JCheckBox myEnableVerboseLoggingCheckBox;
  private JCheckBox myOpenInspectorOnAppLaunchCheckBox;
  private JCheckBox myFormatCodeOnSaveCheckBox;
  private JCheckBox myOrganizeImportsOnSaveCheckBox;
  private JCheckBox myShowStructuredErrors;
  private JCheckBox myIncludeAllStackTraces;
  private JCheckBox mySyncAndroidLibrariesCheckBox;
  private JCheckBox myEnableHotUiCheckBox;
  private JCheckBox myEnableEmbeddedBrowsersCheckBox;
  private JCheckBox myEnableBazelHotRestartCheckBox;

  private JCheckBox myShowAllRunConfigurationsInContextCheckBox;

  private JCheckBox myShowBuildMethodGuides;
  private JCheckBox myShowClosingLabels;
  private FixedSizeButton myCopyButton;
  private JTextArea myFontPackagesTextArea; // This should be changed to a structured list some day.

  private final @NotNull Project myProject;
  private final WorkspaceCache workspaceCache;

  private boolean ignoringSdkChanges = false;

  private String fullVersionString;

  FlutterSettingsConfigurable(@NotNull Project project) {
    this.myProject = project;
    workspaceCache = WorkspaceCache.getInstance(project);
    init();

    myVersionLabel.setText(" ");
  }

  private void init() {
    mySdkCombo.getComboBox().setEditable(true);

    myCopyButton.setSize(ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE);
    myCopyButton.setIcon(PlatformIcons.COPY_ICON);
    myCopyButton.addActionListener(e -> {
      if (fullVersionString != null) {
        CopyPasteManager.getInstance().setContents(new StringSelection(fullVersionString));
      }
    });

    final JTextComponent sdkEditor = (JTextComponent)mySdkCombo.getComboBox().getEditor().getEditorComponent();
    sdkEditor.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull final DocumentEvent e) {
        if (!ignoringSdkChanges) {
          onVersionChanged();
        }
      }
    });

    workspaceCache.subscribe(this::onVersionChanged);

    mySdkCombo.addBrowseFolderListener("Select Flutter SDK Path", null, null,
                                       FileChooserDescriptorFactory.createSingleFolderDescriptor(),
                                       TextComponentAccessor.STRING_COMBOBOX_WHOLE_TEXT);

    myPrivacyPolicy.setListener((linkLabel, data) -> {
      try {
        BrowserLauncher.getInstance().browse(new URI(FlutterBundle.message("flutter.analytics.privacyUrl")));
      }
      catch (URISyntaxException ignore) {
      }
    }, null);

    myFormatCodeOnSaveCheckBox.addChangeListener(
      (e) -> myOrganizeImportsOnSaveCheckBox.setEnabled(myFormatCodeOnSaveCheckBox.isSelected()));
    myShowStructuredErrors.addChangeListener(
      (e) -> myIncludeAllStackTraces.setEnabled(myShowStructuredErrors.isSelected()));

    // There are other experiments so it is alright to show the experiments
    // panel even if the syncAndroidLibraries experiment is hidden.
    // If there are only experiments available on Android studio then add back
    // the following statement:
    // experimentsPanel.setVisible(FlutterUtils.isAndroidStudio());
    mySyncAndroidLibrariesCheckBox.setVisible(FlutterUtils.isAndroidStudio());

    myEnableEmbeddedBrowsersCheckBox.setVisible(true);

    myEnableBazelHotRestartCheckBox.setVisible(WorkspaceCache.getInstance(myProject).isBazel());
  }

  private void createUIComponents() {
    mySdkCombo = new ComboboxWithBrowseButton(new ComboBox<>());
  }

  @Override
  @NotNull
  public String getId() {
    return FlutterConstants.FLUTTER_SETTINGS_PAGE_ID;
  }

  @Nullable
  @Override
  public Runnable enableSearch(String s) {
    return null;
  }

  @Nullable
  @Override
  public JComponent createComponent() {
    return mainPanel;
  }

  @Override
  public boolean isModified() {

    final FlutterSdk sdk = FlutterSdk.getFlutterSdk(myProject);
    final FlutterSettings settings = FlutterSettings.getInstance();
    final String sdkPathInModel = sdk == null ? "" : sdk.getHomePath();
    final String sdkPathInUI = FileUtilRt.toSystemIndependentName(getSdkPathText());

    if (!sdkPathInModel.equals(sdkPathInUI)) {
      return true;
    }

    if (FlutterInitializer.getCanReportAnalytics() != myReportUsageInformationCheckBox.isSelected()) {
      return true;
    }

    if (settings.isReloadOnSave() != myHotReloadOnSaveCheckBox.isSelected()) {
      return true;
    }

    if (settings.isFormatCodeOnSave() != myFormatCodeOnSaveCheckBox.isSelected()) {
      return true;
    }

    if (settings.isOrganizeImportsOnSave() != myOrganizeImportsOnSaveCheckBox.isSelected()) {
      return true;
    }

    if (settings.isShowBuildMethodGuides() != myShowBuildMethodGuides.isSelected()) {
      return true;
    }

    if (settings.isShowClosingLabels() != myShowClosingLabels.isSelected()) {
      return true;
    }

    if (settings.isShowStructuredErrors() != myShowStructuredErrors.isSelected()) {
      return true;
    }

    if (settings.isIncludeAllStackTraces() != myIncludeAllStackTraces.isSelected()) {
      return true;
    }

    if (settings.isOpenInspectorOnAppLaunch() != myOpenInspectorOnAppLaunchCheckBox.isSelected()) {
      return true;
    }

    if (settings.isVerboseLogging() != myEnableVerboseLoggingCheckBox.isSelected()) {
      return true;
    }

    if (settings.isSyncingAndroidLibraries() != mySyncAndroidLibrariesCheckBox.isSelected()) {
      return true;
    }

    if (settings.isEnableHotUi() != myEnableHotUiCheckBox.isSelected()) {
      return true;
    }

    if (settings.isEnableEmbeddedBrowsers() != myEnableEmbeddedBrowsersCheckBox.isSelected()) {
      return true;
    }

    if (settings.isEnableBazelHotRestart() != myEnableBazelHotRestartCheckBox.isSelected()) {
      return true;
    }

    if (!settings.getFontPackages().equals(myFontPackagesTextArea.getText())) {
      return true;
    }

    //noinspection RedundantIfStatement
    if (settings.showAllRunConfigurationsInContext() != myShowAllRunConfigurationsInContextCheckBox.isSelected()) {
      return true;
    }

    return false;
  }

  @Override
  public void apply() throws ConfigurationException {
    // Bazel workspaces do not specify a sdk path so we do not need to update the sdk path if using
    // a bazel workspace.
    if (!workspaceCache.isBazel()) {
      final String errorMessage = FlutterSdkUtil.getErrorMessageIfWrongSdkRootPath(getSdkPathText());
      if (errorMessage != null) {
        throw new ConfigurationException(errorMessage);
      }

      final String sdkHomePath = getSdkPathText();
      if (FlutterSdkUtil.isFlutterSdkHome(sdkHomePath)) {
        ApplicationManager.getApplication().runWriteAction(() -> {
          FlutterSdkUtil.setFlutterSdkPath(myProject, sdkHomePath);
          FlutterSdkUtil.enableDartSdk(myProject);
          ApplicationManager.getApplication().executeOnPooledThread(() -> {
            final FlutterSdk sdk = FlutterSdk.forPath(sdkHomePath);
            if (sdk != null) {
              sdk.queryFlutterChannel(false);
            }
          });
        });
      }
    }

    FlutterInitializer.setCanReportAnalytics(myReportUsageInformationCheckBox.isSelected());

    final FlutterSettings settings = FlutterSettings.getInstance();
    final String oldFontPackages = settings.getFontPackages();
    settings.setReloadOnSave(myHotReloadOnSaveCheckBox.isSelected());
    settings.setFormatCodeOnSave(myFormatCodeOnSaveCheckBox.isSelected());
    settings.setOrganizeImportsOnSave(myOrganizeImportsOnSaveCheckBox.isSelected());

    settings.setShowBuildMethodGuides(myShowBuildMethodGuides.isSelected());
    settings.setShowClosingLabels(myShowClosingLabels.isSelected());
    settings.setShowStructuredErrors(myShowStructuredErrors.isSelected());
    settings.setIncludeAllStackTraces(myIncludeAllStackTraces.isSelected());
    settings.setOpenInspectorOnAppLaunch(myOpenInspectorOnAppLaunchCheckBox.isSelected());
    settings.setVerboseLogging(myEnableVerboseLoggingCheckBox.isSelected());
    settings.setSyncingAndroidLibraries(mySyncAndroidLibrariesCheckBox.isSelected());
    settings.setEnableHotUi(myEnableHotUiCheckBox.isSelected());
    settings.setEnableEmbeddedBrowsers(myEnableEmbeddedBrowsersCheckBox.isSelected());
    settings.setEnableBazelHotRestart(myEnableBazelHotRestartCheckBox.isSelected());
    settings.setShowAllRunConfigurationsInContext(myShowAllRunConfigurationsInContextCheckBox.isSelected());
    settings.setFontPackages(myFontPackagesTextArea.getText());

    reset(); // because we rely on remembering initial state
    checkFontPackages(settings.getFontPackages(), oldFontPackages);
  }

  @Override
  public void reset() {
    final FlutterSdk sdk = FlutterSdk.getFlutterSdk(myProject);
    final String path = sdk != null ? sdk.getHomePath() : "";

    // Set this after populating the combo box to display correctly when the Flutter SDK is unset.
    // (This can happen if the user changed the Dart SDK.)
    try {
      ignoringSdkChanges = true;
      FlutterSdkUtil.addKnownSDKPathsToCombo(mySdkCombo.getComboBox());
      mySdkCombo.getComboBox().getEditor().setItem(FileUtil.toSystemDependentName(path));
    }
    finally {
      ignoringSdkChanges = false;
    }

    onVersionChanged();

    myReportUsageInformationCheckBox.setSelected(FlutterInitializer.getCanReportAnalytics());

    final FlutterSettings settings = FlutterSettings.getInstance();
    myHotReloadOnSaveCheckBox.setSelected(settings.isReloadOnSave());
    myFormatCodeOnSaveCheckBox.setSelected(settings.isFormatCodeOnSave());
    myOrganizeImportsOnSaveCheckBox.setSelected(settings.isOrganizeImportsOnSave());

    myShowBuildMethodGuides.setSelected(settings.isShowBuildMethodGuides());

    myShowClosingLabels.setSelected(settings.isShowClosingLabels());

    myShowStructuredErrors.setSelected(settings.isShowStructuredErrors());
    myIncludeAllStackTraces.setSelected(settings.isIncludeAllStackTraces());
    myOpenInspectorOnAppLaunchCheckBox.setSelected(settings.isOpenInspectorOnAppLaunch());
    myEnableVerboseLoggingCheckBox.setSelected(settings.isVerboseLogging());
    mySyncAndroidLibrariesCheckBox.setSelected(settings.isSyncingAndroidLibraries());

    myEnableHotUiCheckBox.setSelected(settings.isEnableHotUi());

    myEnableEmbeddedBrowsersCheckBox.setSelected(settings.isEnableEmbeddedBrowsers());
    myEnableBazelHotRestartCheckBox.setSelected(settings.isEnableBazelHotRestart());

    myOrganizeImportsOnSaveCheckBox.setEnabled(myFormatCodeOnSaveCheckBox.isSelected());
    myIncludeAllStackTraces.setEnabled(myShowStructuredErrors.isSelected());

    myShowAllRunConfigurationsInContextCheckBox.setSelected(settings.showAllRunConfigurationsInContext());
    myFontPackagesTextArea.setText(settings.getFontPackages());
  }

  private void onVersionChanged() {
    final Workspace workspace = workspaceCache.get();
    if (workspaceCache.isBazel()) {
      if (mySdkCombo.isEnabled()) {
        // The workspace is not null if workspaceCache.isBazel() is true.
        assert (workspace != null);

        mySdkCombo.setEnabled(false);
        mySdkCombo.getComboBox().getEditor()
          .setItem(workspace.getRoot().getPath() + '/' + workspace.getSdkHome() + " <set by bazel project>");
      }
    }
    else {
      mySdkCombo.setEnabled(true);
    }

    final FlutterSdk sdk = FlutterSdk.forPath(getSdkPathText());
    if (sdk == null) {
      // Clear the label out with a non-empty string, so that the layout doesn't give this element 0 height.
      myVersionLabel.setText(" ");
      fullVersionString = null;
      return;
    }

    final ModalityState modalityState = ModalityState.current();

    // TODO(devoncarew): Switch this to expecting json output.
    sdk.flutterVersion().start((ProcessOutput output) -> {
      final String fullVersionText = output.getStdout();
      fullVersionString = fullVersionText;

      final String[] lines = StringUtil.splitByLines(fullVersionText);
      final String singleLineVersion = lines.length > 0 ? lines[0] : "";
      ApplicationManager.getApplication().invokeLater(() -> updateVersionTextIfCurrent(sdk, singleLineVersion), modalityState);
    }, null);
  }

  /***
   * Sets the version text but only if we don't have stale data.
   *
   * @param sdk the SDK that was current at the time.
   */
  private void updateVersionTextIfCurrent(@NotNull FlutterSdk sdk, @NotNull String value) {
    final FlutterSdk current = FlutterSdk.forPath(getSdkPathText());
    if (current == null) {
      myVersionLabel.setText(" ");
    }
    else {
      myVersionLabel.setText(value);
    }
  }

  @Override
  public void disposeUIResources() {

  }

  @Override
  @Nls
  public String getDisplayName() {
    return FLUTTER_SETTINGS_PAGE_NAME;
  }

  @Nullable
  @Override
  public String getHelpTopic() {
    return FLUTTER_SETTINGS_HELP_TOPIC;
  }

  @NotNull
  private String getSdkPathText() {
    return FileUtilRt.toSystemIndependentName(mySdkCombo.getComboBox().getEditor().getItem().toString().trim());
  }

  private void checkFontPackages(String value, String previous) {
    if (value != null) {
      final String[] packages = value.split(FontPreviewProcessor.PACKAGE_SEPARATORS);
      for (String name : packages) {
        final String message = FontPreviewProcessor.UNSUPPORTED_PACKAGES.get(name.trim());
        if (message != null) {
          displayNotification(message);
        }
      }
      if (!value.equals(previous)) {
        FontPreviewProcessor.reanalyze(myProject);
      }
    }
  }

  private void displayNotification(String message) {
    final Notification notification = new Notification(
      FlutterMessages.FLUTTER_LOGGING_NOTIFICATION_GROUP_ID,
      FlutterBundle.message("icon.preview.disallow.package.title"),
      message,
      NotificationType.INFORMATION);
    notification.setIcon(FlutterIcons.Flutter);
    Notifications.Bus.notify(notification, myProject);
  }

  public static void openFlutterSettings(@NotNull final Project project) {
    ShowSettingsUtilImpl.showSettingsDialog(project, FlutterConstants.FLUTTER_SETTINGS_PAGE_ID, "");
  }
}
