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
package com.intershop.gradle.versionrecommender

import com.intershop.gradle.versionrecommender.extension.VersionRecommenderExtension
import com.intershop.gradle.versionrecommender.extension.PublicationXmlGenerator
import com.intershop.gradle.versionrecommender.util.NoVersionException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.XmlProvider
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyResolveDetails
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.publish.ivy.IvyPublication
import org.gradle.api.publish.ivy.plugins.IvyPublishPlugin
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.specs.Spec

/**
 * This plugin applies functionality for version handling in large projects.
 *
 * It provides the possibility
 * <ul>
 *     <li>to store version numbers in</li>
 *     <ul>
 *         <li>Ivy files</li>
 *         <li>Maven BOM files</li>
 *         <li>Properties files</li>
 *         <li>Simple static properties configuration</li>
 *     </ul>
 *     <li>to update versions</li>
 * </ul>
 *
 * It adds also some functionality for the creation of BOM und Ivy filters.
 *
 * This plugin applies the functionality always to the root project!
 */
class VersionRecommenderPlugin implements Plugin<Project> {

    private VersionRecommenderExtension extension

    /**
     * Apply this plugin to the given target project.
     *
     * @param project The target project
     */
    @Override
    void apply(final Project project) {
        // helper method to make sure this is
        // a plugin for the root project.
        applyToRootProject(project.getRootProject())
    }

    /**
     * Create extension and add the functionality to
     * the given project. This is always the root project.
     *
     * @param project The target project
     */
    private void applyToRootProject(Project project) {
        project.logger.info('Create extension {} for {}', VersionRecommenderExtension.EXTENSIONNAME, project.name)

        // create extension on root project
        extension = project.extensions.findByType(VersionRecommenderExtension) ?: project.extensions.create(VersionRecommenderExtension.EXTENSIONNAME, VersionRecommenderExtension, project)


        // add version recommendation to to root project.
        applyRecommendation(project)
        applyIvyVersionRecommendation(project)
        applyMvnVersionRecommendation(project)

        // add version recommendation to to sub projects.
        project.getSubprojects().each {
            applyRecommendation(it)
            applyIvyVersionRecommendation(it)
            applyMvnVersionRecommendation(it)
        }

        // add support for filter creation to the root project
        if(! project.extensions.findByType(PublicationXmlGenerator)) {
            project.extensions.create(PublicationXmlGenerator.EXTENSIONNAME, PublicationXmlGenerator, project)
        }
    }

    /**
     * Apply functionality for an Ivy Publication.
     * Version information will be added to an Ivy File
     * with empty rev attributes.
     *
     * @param project The target project
     */
    private void applyIvyVersionRecommendation(Project project) {
        project.plugins.withType(IvyPublishPlugin) {
            project.publishing {
                publications.withType(IvyPublication) {
                    IvyPublication publication = delegate

                    descriptor.withXml { XmlProvider xml ->
                        def rootNode = xml.asNode()
                        def dependenciesWithoutRevAttribute = rootNode.dependencies.dependency.findAll { !it.@rev }

                        dependenciesWithoutRevAttribute.each { dependencyNode ->
                            def configurationName = (dependencyNode.@conf).split('->')[0]

                            def configuration = project.configurations.findByName(configurationName)

                            if (!configuration) {
                                project.logger.warn("Failed to provide 'rev' attribute for dependency '{}:{}' in publication '{}' as there is no  project configuration of the name '{}'",
                                        dependencyNode.@org, dependencyNode.@name, publication.name, configurationName)
                                return
                            }

                            def resolvedDependencies = configuration.resolvedConfiguration.getFirstLevelModuleDependencies ({ Dependency resolvedDependency ->
                                resolvedDependency.name == dependencyNode.@name && resolvedDependency.group == dependencyNode.@org
                            } as Spec<Dependency>)

                            if (resolvedDependencies.size() == 0) {
                                project.logger.warn("Failed to provide 'rev' attribute for dependency '{}:{}' in publication '{}' as there is no dependency of that name in resolved project configuration '{}'",
                                        dependencyNode.@org, dependencyNode.@name, publication.name, configurationName)
                                return
                            }

                            ResolvedDependency resolvedDependency = (resolvedDependencies as List)[0]
                            dependencyNode.@rev = resolvedDependency.module.id.version
                        }
                    }
                }
            }
        }
    }

    /**
     * Apply functionality for a Maven Publication.
     * Version information will be added to an pom File
     * with empty version or missing version nodes.
     *
     * @param project The target project
     */
    private void applyMvnVersionRecommendation(Project project) {
        project.plugins.withType(MavenPublishPlugin) {
            project.publishing {
                publications {
                    withType(MavenPublication) {
                        MavenPublication publication = delegate

                        pom.withXml { XmlProvider xml ->
                            def rootNode = xml.asNode()
                            def dependenciesWithoutVersion = rootNode.dependencies.dependency.findAll {
                                !it.version.text()
                            }

                            def dependenciesWithoutVersionFromMgmt = rootNode.dependencyManagement.dependencies.dependency.findAll {
                                !it.version.text()
                            }

                            dependenciesWithoutVersion.addAll(dependenciesWithoutVersionFromMgmt)

                            dependenciesWithoutVersion.each { dependencyNode ->
                                def configurationName = dependencyNode.scope.text()
                                def configuration = project.configurations.findByName(configurationName)

                                if (!configuration) {
                                    project.logger.warn("Failed to provide 'version' attribute for dependency '{}:{}' in publication '{}' as there is no  project configuration of the name '{}'",
                                            dependencyNode.groupId.text(), dependencyNode.artifactId.text(), publication.name, configurationName)
                                    return
                                }

                                def resolvedDependencies = configuration.resolvedConfiguration.getFirstLevelModuleDependencies({ Dependency resolvedDependency ->
                                    resolvedDependency.name == dependencyNode.artifactId.text() && resolvedDependency.group == dependencyNode.groupId.text()
                                } as Spec<Dependency>)

                                if (resolvedDependencies.size() == 0) {
                                    project.logger.warn("Failed to provide 'version' attribute for dependency '{}:{}' in publication '{}' as there is no dependency of that name in resolved project configuration '{}'",
                                            dependencyNode.artifactId.text(), dependencyNode.artifactId.text(), publication.name, configurationName)
                                    return
                                }

                                ResolvedDependency resolvedDependency = (resolvedDependencies as List)[0]
                                dependencyNode.appendNode((new Node(null, 'version'))).setValue(resolvedDependency.module.id.version)
                            }
                        }
                    }
                }
            }
        }

    }

    /**
     * Apply the basic version recommendation for resolving
     * version numbers from other(external) sources.
     * This initialization will also add all the necessary tasks.
     *
     * @param project The target project
     */
    private void applyRecommendation(Project project) {
        project.afterEvaluate {
            extension.provider.initializeVersions()
        }
        project.getConfigurations().all { Configuration conf ->
            if(! extension.getExcludeProjectsbyName().contains(project.getName())) {
                conf.getResolutionStrategy().eachDependency { DependencyResolveDetails details ->
                    if (!details.requested.version || extension.forceRecommenderVersion) {

                        String rv = extension.provider.getVersion(details.requested.group, details.requested.name)

                        if (details.requested.version && !(rv))
                            rv = details.requested.version

                        if (rv) {
                            details.useVersion(rv)
                        } else {
                            throw new NoVersionException("Version for '${details.requested.group}:${details.requested.name}' not found! Please check your dependency configuration and the version recommender version.")
                        }
                    }
                }
            } else {
                project.logger.warn('Project "{}" is not handled by this version recommender plugin.', project.getName())
            }
        }
    }

}