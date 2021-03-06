/*
 * Copyright 2015 Intershop Communications AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.intershop.gradle.versionrecommender.tasks

import com.intershop.gradle.versionrecommender.recommendation.RecommendationProvider
import com.intershop.gradle.versionrecommender.scm.IScmClient
import com.intershop.gradle.versionrecommender.scm.ScmClient
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * <p>Store update version</p>
 * <p>Stored updated version information of a configured provider</p>
 * <p>The main functionality is implemented in the connected providers.</p>
 */
@CompileStatic
class StoreUpdateVersion extends DefaultTask {

    /**
     * Version recommendation provider of this task
     */
    @Input
    RecommendationProvider provider

    /**
     * The file with the stored version information
     */
    @OutputFile
    File versionFile

    /**
     * Task action
     */
    @TaskAction
    void storeUpdateVersion(){
        try {
            versionFile = provider.store(getVersionFile())
            provider.initializeVersion()
            if(versionFile && project.hasProperty('scmCommit') && project.property('scmCommit').toString().toBoolean()) {
                List<File> fileList = []
                fileList.add(versionFile)
                IScmClient client = new ScmClient(project)
                client.commit(fileList, 'Commit update changes by Gradle plugin "com.intershop.gradle.versionrecommender"')
            }
        }catch (IOException ex) {
            throw new GradleException('It was not possible to store changes!')
        }
    }

    /**
     * Description
     *
     * @return "Store changes from working dir for 'provider name'"
     */
    @Internal
    @Override
    String getDescription() {
        return "Store changes from working dir for ${provider.getName()}"
    }

    /**
     * Group
     *
     * @return "Provider name - Version Recommendation"
     */
    @Internal
    @Override
    String getGroup() {
        return "${provider.getName()} - Version Recommendation"
    }
}
