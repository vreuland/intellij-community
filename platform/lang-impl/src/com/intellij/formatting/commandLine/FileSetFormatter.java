/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.formatting.commandLine;

import com.intellij.openapi.application.AccessToken;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.impl.NonProjectFileWritingAccessProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ex.ProjectManagerEx;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.util.PlatformUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

class FileSetFormatter extends FileSetProcessor {
  private final static String PROJECT_DIR_PREFIX = PlatformUtils.getPlatformPrefix() + ".format.";
  private final static String PROJECT_DIR_SUFFIX = ".tmp";

  private final @NotNull String myProjectUID;
  private @Nullable Project myProject;
  private MessageOutput myMessageOutput;

  FileSetFormatter(@NotNull String fileSpec, @NotNull MessageOutput messageOutput) {
    super(fileSpec);
    myMessageOutput = messageOutput;
    myProjectUID = UUID.randomUUID().toString();
  }

  @Nullable
  private File createProject() throws IOException {
    ProjectManagerEx projectManager = (ProjectManagerEx)ProjectManager.getInstance();
    File projectDir = createProjectDir();
    myProject = projectManager.createProject(myProjectUID, projectDir.getPath());
    if (myProject != null) {
      projectManager.openProject(myProject);
      return projectDir;
    }
    return null;
  }

  private File createProjectDir() throws IOException {
    File tempDir = FileUtil.createTempDirectory(PROJECT_DIR_PREFIX, myProjectUID + PROJECT_DIR_SUFFIX);
    File projectDir = new File(tempDir.getPath() + File.separator + ".idea");
    if (projectDir.mkdirs()) {
      return projectDir;
    }
    throw new IOException("Cannot create a temporary project at " + projectDir);
  }

  private void closeProject() {
    if (myProject != null) {
      ProjectManager.getInstance().closeProject(myProject);
      AccessToken writeToken = ApplicationManager.getApplication().acquireWriteActionLock(this.getClass());
      Disposer.dispose(myProject);
      writeToken.finish();
    }
  }

  @Override
  public void processFiles() throws IOException {
    File projectDir = createProject();
    if (projectDir != null) {
      super.processFiles();
      closeProject();
    }
  }

  @Override
  protected void processFile(@NotNull VirtualFile virtualFile) {
    if (myProject != null) {
      VfsUtil.markDirtyAndRefresh(false, false, false, virtualFile);
      myMessageOutput.info("Formatting " + virtualFile.getCanonicalPath() + "...");
      Document document = FileDocumentManager.getInstance().getDocument(virtualFile);
      if (document != null) {
        PsiFile psiFile = PsiDocumentManager.getInstance(myProject).getPsiFile(document);
        NonProjectFileWritingAccessProvider.allowWriting(virtualFile);
        if (psiFile != null) {
          reformatFile(myProject, psiFile, document);
        }
        FileDocumentManager.getInstance().saveDocument(document);
      }
      FileEditorManager editorManager = FileEditorManager.getInstance(myProject);
      VirtualFile[] openFiles = editorManager.getOpenFiles();
      for (VirtualFile openFile : openFiles) {
        editorManager.closeFile(openFile);
      }
      myMessageOutput.info("OK\n");
    }
  }

  private void reformatFile(@NotNull Project project, @NotNull PsiFile file, @NotNull Document document) {
    AccessToken writeToken = ApplicationManager.getApplication().acquireWriteActionLock(this.getClass());
    try {
      CommandProcessor.getInstance().executeCommand(
        myProject,
        () -> {
          CodeStyleManager.getInstance(project).reformatText(file, 0, file.getTextLength());
          PsiDocumentManager.getInstance(project).commitDocument(document);
        }, null, null);
    }
    finally {
      writeToken.finish();
    }
  }

}
